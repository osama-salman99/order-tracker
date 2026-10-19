package work.osmosis.ordersapp.extract;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out when a parcel is expected, from the sentence the shop wrote about it.
 *
 * <p>This is the field the whole home page is sorted by, and it is the one shops are most creative
 * about. Everything below appears in this mailbox:
 *
 * <ul>
 *   <li>a full date — "Scheduled Delivery Date: Monday, 13/07/2026";
 *   <li>a window — "Estimated Delivery Date: 02/07/2026 - 03/07/2026";
 *   <li>a day and a month but no year at all — "Bezorgdatum: 7 september";
 *   <li>a weekday and nothing else — "Woensdag komen we bij je langs";
 *   <li>only a time of day — "We will deliver between 11.10 and 12.10".
 * </ul>
 *
 * <p>The last three are only meaningful next to the date the email was sent, which is why every
 * method here takes it. A weekday means the next one to come round; a month with no year means
 * whichever year puts the date near the email rather than eleven months away from it; a time alone
 * means today.
 */
public final class DateRules {

    /**
     * Phrases that introduce a delivery date. Everything within the next stretch of text is where
     * the date is looked for — a date found anywhere else in the email is far more likely to be the
     * order date, a returns deadline or an offer expiry.
     *
     * <p>A phrase written as a heading — ending its line, with or without a colon — is followed onto
     * the next line: IKEA prints "Delivery date:" and puts "Monday 19 October 2026" underneath it,
     * Coolblue does the same under "Your delivery day". A pick-up date counts as the delivery date:
     * it is the day the parcel can be collected.
     */
    private static final Pattern ANCHOR = Pattern.compile(
            "(?:bezorgdatum|bezorgmoment|leverdatum|leveringsdatum|afleverdatum|verwachte?\\s+(?:levering|bezorging"
                    + "|leveringsdatum|bezorgdatum|aankomst)|wordt\\s+bezorgd|bezorgen\\s+we|we\\s+bezorgen"
                    + "|komen\\s+we\\s+bij\\s+je\\s+langs|bezorgd\\s+op|in\\s+huis\\s+op|verwacht"
                    + "|delivery\\s+date|estimated\\s+delivery|scheduled\\s+delivery|expected\\s+delivery"
                    + "|expected\\s+to\\s+arrive|will\\s+(?:be\\s+delivered|arrive|deliver)|arriving"
                    + "|out\\s+for\\s+delivery|delivered\\s+on|deliver(?:y|ed)?\\s+between|delivery\\s+day|bezorgdag"
                    + "|pick\\s+up\\s+your\\s+(?:order|parcel|package)\\s+from|ophalen\\s+vanaf|beschikbaar\\s+vanaf)"
                    + "((?:\\h*:?\\h*(?:\\R\\h*)+)?[^\\n]{0,90})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SLASHED = Pattern.compile("\\b(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})\\b");
    private static final Pattern ISO = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");

    private static final Map<String, Integer> MONTHS = months();
    private static final Map<String, DayOfWeek> WEEKDAYS = weekdays();

    private static final String MONTH_ALTERNATIVES = String.join("|", MONTHS.keySet());
    private static final Pattern DAY_MONTH = Pattern.compile(
            "\\b(\\d{1,2})(?:st|nd|rd|th|e)?\\s+(?:of\\s+)?(" + MONTH_ALTERNATIVES + ")\\b(?:\\s+(\\d{4}))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MONTH_DAY = Pattern.compile(
            "\\b(" + MONTH_ALTERNATIVES + ")\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b(?:,?\\s+(\\d{4}))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WEEKDAY = Pattern.compile(
            "\\b(" + String.join("|", WEEKDAYS.keySet()) + ")\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TODAY = Pattern.compile("\\b(vandaag|today)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOMORROW = Pattern.compile("\\b(morgen|tomorrow)\\b", Pattern.CASE_INSENSITIVE);
    /** A clock time and no date, which shops use to mean a window later the same day. */
    private static final Pattern TIME_ONLY = Pattern.compile("\\b\\d{1,2}[:.]\\d{2}\\b");

    private DateRules() {}

    /**
     * The delivery date the email promises, or null if it does not promise one.
     *
     * @param text the flattened body, with the subject line prepended
     * @param sentOn the day the email was sent, which is what relative wording is measured from
     */
    public static DeliveryEstimate find(String text, LocalDate sentOn) {
        if (text == null || text.isBlank() || sentOn == null) {
            return null;
        }
        Matcher anchor = ANCHOR.matcher(text);
        DeliveryEstimate weakest = null;
        while (anchor.find()) {
            String window = anchor.group(1);
            DeliveryEstimate exact = readDates(window, sentOn);
            if (exact != null) {
                return exact;
            }
            // Keep a weekday or a bare time in reserve: a later anchor may still hold a real date,
            // and a real date always beats an inference.
            if (weakest == null) {
                weakest = readRelative(window, sentOn);
            }
        }
        return weakest != null ? weakest : dayNamedFirst(text, sentOn);
    }

    /**
     * The promise written the other way round, with the day at the front: "Morgen komen we uw pakket
     * van <verkoper> afleveren", or DHL's "Dinsdag komen we bij je langs". Every anchor above expects the delivering word to come first, so a
     * sentence that opens on the day slips past all of them and the email promises nothing. Read only
     * when nothing else in the email gave a date, since a day word on its own means very little.
     */
    private static DeliveryEstimate dayNamedFirst(String text, LocalDate sentOn) {
        Matcher m = DAY_FIRST.matcher(text);
        if (!m.find()) {
            return null;
        }
        String day = m.group(1).toLowerCase(Locale.ROOT);
        if (WEEKDAYS.containsKey(day)) {
            return DeliveryEstimate.on(nextOccurrence(WEEKDAYS.get(day), sentOn));
        }
        return DeliveryEstimate.on(day.equals("morgen") || day.equals("tomorrow") ? sentOn.plusDays(1) : sentOn);
    }

    private static final Pattern DAY_FIRST = Pattern.compile(
            "\\b(vandaag|morgen|today|tomorrow|" + String.join("|", WEEKDAYS.keySet()) + ")\\b[^\\n]{0,60}?"
                    + "\\b(?:bezorg\\w*|aflever\\w*|kom(?:t|en)\\s+we|deliver\\w*)",
            Pattern.CASE_INSENSITIVE);

    /** Reads a written-out date, and a second one after it when the shop gave a window. */
    private static DeliveryEstimate readDates(String window, LocalDate sentOn) {
        List<LocalDate> dates = new java.util.ArrayList<>();
        Matcher iso = ISO.matcher(window);
        while (iso.find()) {
            dates.add(safeDate(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)),
                    Integer.parseInt(iso.group(3))));
        }
        Matcher slashed = SLASHED.matcher(window);
        while (slashed.find()) {
            int first = Integer.parseInt(slashed.group(1));
            int second = Integer.parseInt(slashed.group(2));
            int year = Integer.parseInt(slashed.group(3));
            // Dutch and most European mail writes the day first. Only when that is impossible and
            // the other reading works is it treated as an American date.
            boolean dayFirst = first > 12 || second <= 12;
            dates.add(dayFirst ? safeDate(year, second, first) : safeDate(year, first, second));
        }
        Matcher dayMonth = DAY_MONTH.matcher(window);
        while (dayMonth.find()) {
            dates.add(withInferredYear(Integer.parseInt(dayMonth.group(1)), monthOf(dayMonth.group(2)),
                    dayMonth.group(3), sentOn));
        }
        Matcher monthDay = MONTH_DAY.matcher(window);
        while (monthDay.find()) {
            dates.add(withInferredYear(Integer.parseInt(monthDay.group(2)), monthOf(monthDay.group(1)),
                    monthDay.group(3), sentOn));
        }
        dates.removeIf(java.util.Objects::isNull);
        if (dates.isEmpty()) {
            return null;
        }
        dates.sort(java.util.Comparator.naturalOrder());
        return new DeliveryEstimate(dates.get(0), dates.get(dates.size() - 1));
    }

    /** Reads wording that only means something relative to the day the email was sent. */
    private static DeliveryEstimate readRelative(String window, LocalDate sentOn) {
        if (TODAY.matcher(window).find()) {
            return DeliveryEstimate.on(sentOn);
        }
        if (TOMORROW.matcher(window).find()) {
            return DeliveryEstimate.on(sentOn.plusDays(1));
        }
        Matcher weekday = WEEKDAY.matcher(window);
        if (weekday.find()) {
            return DeliveryEstimate.on(nextOccurrence(WEEKDAYS.get(weekday.group(1).toLowerCase(Locale.ROOT)), sentOn));
        }
        if (TIME_ONLY.matcher(window).find()) {
            return DeliveryEstimate.on(sentOn);
        }
        return null;
    }

    /**
     * The next time that weekday comes round, counting the day the email was sent as valid: a
     * carrier writing "Woensdag komen we langs" on a Wednesday means that morning, not next week.
     */
    private static LocalDate nextOccurrence(DayOfWeek day, LocalDate from) {
        LocalDate candidate = from;
        for (int i = 0; i < 7; i++) {
            if (candidate.getDayOfWeek() == day) {
                return candidate;
            }
            candidate = candidate.plusDays(1);
        }
        return from;
    }

    /**
     * Fills in a missing year. Shops leave it out constantly — "Bezorgdatum: 7 september" — and
     * assuming the year the email was sent in is wrong every December. The year chosen is the one
     * that puts the delivery just after the email rather than months either side of it.
     */
    private static LocalDate withInferredYear(int day, int month, String statedYear, LocalDate sentOn) {
        if (month == 0) {
            return null;
        }
        if (statedYear != null) {
            return safeDate(Integer.parseInt(statedYear), month, day);
        }
        LocalDate best = null;
        for (int year : new int[] {sentOn.getYear() - 1, sentOn.getYear(), sentOn.getYear() + 1}) {
            LocalDate candidate = safeDate(year, month, day);
            if (candidate == null) {
                continue;
            }
            // A delivery a few days before the email is possible — a "was delivered" note — but a
            // date far in the past is the wrong year.
            long days = java.time.temporal.ChronoUnit.DAYS.between(sentOn, candidate);
            if (days < -14 || days > 300) {
                continue;
            }
            if (best == null || candidate.isBefore(best)) {
                best = candidate;
            }
        }
        return best;
    }

    private static LocalDate safeDate(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    private static int monthOf(String name) {
        return MONTHS.getOrDefault(name.toLowerCase(Locale.ROOT), 0);
    }

    private static Map<String, Integer> months() {
        Map<String, Integer> map = new LinkedHashMap<>();
        String[] dutch = {"januari", "februari", "maart", "april", "mei", "juni", "juli", "augustus",
                "september", "oktober", "november", "december"};
        String[] english = {"january", "february", "march", "april", "may", "june", "july", "august",
                "september", "october", "november", "december"};
        // Longest names first so the pattern cannot match "jan" inside "januari" and stop there.
        for (int i = 0; i < 12; i++) {
            map.put(dutch[i], i + 1);
            map.put(english[i], i + 1);
        }
        String[] shortDutch = {"jan", "feb", "mrt", "apr", "mei", "jun", "jul", "aug", "sep", "okt", "nov", "dec"};
        String[] shortEnglish = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sept", "oct", "nov", "dec"};
        for (int i = 0; i < 12; i++) {
            map.putIfAbsent(shortDutch[i], i + 1);
            map.putIfAbsent(shortEnglish[i], i + 1);
        }
        return map;
    }

    private static Map<String, DayOfWeek> weekdays() {
        Map<String, DayOfWeek> map = new LinkedHashMap<>();
        String[] dutch = {"maandag", "dinsdag", "woensdag", "donderdag", "vrijdag", "zaterdag", "zondag"};
        String[] english = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"};
        for (int i = 0; i < 7; i++) {
            map.put(dutch[i], DayOfWeek.of(i + 1));
            map.put(english[i], DayOfWeek.of(i + 1));
        }
        return map;
    }
}
