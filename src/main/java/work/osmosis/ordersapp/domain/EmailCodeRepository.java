package work.osmosis.ordersapp.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailCodeRepository extends JpaRepository<EmailCode, Long> {

    List<EmailCode> findByShopKeyAndCodeIn(String shopKey, Collection<String> codes);

    void deleteByEmailId(Long emailId);
}
