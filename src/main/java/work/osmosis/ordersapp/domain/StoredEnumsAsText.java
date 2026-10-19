package work.osmosis.ordersapp.domain;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * Turns the columns holding a stage or a status into ordinary text, once.
 *
 * <p>H2 has a column type that lists the only values it will accept, and Hibernate reaches for it
 * when a field is a Java enum. That is a quiet trap. The list is written when the table is first
 * created and is never revisited, so the day a new stage is added — "ready for pickup", say — the
 * database rejects every row carrying it while the code, the tests and any freshly created database
 * all agree it is perfectly valid. Nothing fails until the change meets the real data, and then
 * every affected email is set aside with a database error instead of a stage.
 *
 * <p>Widening the list on each new value would only postpone it. Storing the name as plain text
 * removes the problem instead: the set of stages then lives in one place, the {@code Stage} enum,
 * which is where a reader would look for it. Nothing is lost by doing so — the values are already
 * stored as their names, so the conversion rewrites no data, and the application has always been
 * the thing that decides which names are meaningful.
 *
 * <p>Runs after the schema has been brought up to date and before anything reads mail, and does
 * nothing at all on a database whose columns are already text — including every new one, since the
 * entities now ask for text outright.
 */
@Component
@DependsOn("entityManagerFactory")
public class StoredEnumsAsText {

    private static final Logger log = LoggerFactory.getLogger(StoredEnumsAsText.class);

    /** Every column that Hibernate would otherwise have pinned to a fixed list of values. */
    private static final List<String[]> COLUMNS = List.of(
            new String[] {"CUSTOMER_ORDER", "STAGE", "varchar(30)"},
            new String[] {"RECEIVED_EMAIL", "STAGE", "varchar(30)"},
            new String[] {"RECEIVED_EMAIL", "STATUS", "varchar(20)"});

    private final DataSource dataSource;

    public StoredEnumsAsText(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    void convert() {
        try (Connection connection = dataSource.getConnection()) {
            for (String[] column : COLUMNS) {
                if (isFixedList(connection, column[0], column[1])) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("alter table " + column[0] + " alter column " + column[1]
                                + " set data type " + column[2]);
                    }
                    log.info("Stored {}.{} as text, so a new value no longer needs a schema change",
                            column[0], column[1]);
                }
            }
        } catch (Exception e) {
            // Worth saying loudly, but not worth refusing to start over: on a database that was
            // never converted the service still works for every value that already existed.
            log.error("Could not store the stage and status columns as text; adding a new stage will"
                    + " fail until this is sorted out", e);
        }
    }

    private boolean isFixedList(Connection connection, String table, String column) throws Exception {
        String sql = "select data_type from information_schema.columns"
                + " where table_schema = 'PUBLIC' and table_name = ? and column_name = ?";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, table);
            query.setString(2, column);
            try (ResultSet found = query.executeQuery()) {
                return found.next() && "ENUM".equalsIgnoreCase(found.getString(1));
            }
        }
    }
}
