package com.mawai.wiibquant.external.etf;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EtfFlowScraperTest {

    @Test
    void parseAllReturnsEveryDailyRowAscendingSkippingTotalRow() throws Exception {
        EtfFlowScraper scraper = new EtfFlowScraper(null);
        List<EtfFlowScraper.EtfFlowPoint> points = scraper.parseAll(sampleHtml());

        // 样本含 2 个日期行 + 1 个 "Total" 汇总行；汇总行 Date 列非日期 → 自然排除
        assertEquals(2, points.size());
        assertEquals(java.time.LocalDate.of(2026, 4, 23), points.get(0).date());   // 升序：先 23 日
        assertEquals(new BigDecimal("51.1"), points.get(0).totalFlowUsdMillion());
        assertEquals(java.time.LocalDate.of(2026, 4, 24), points.get(1).date());
        assertEquals(new BigDecimal("111.7"), points.get(1).totalFlowUsdMillion());
    }

    /** 与 collectOnce 同一套组合（finalizedPoints(parseAll)）：占位空行被剔，落库的是上一个已完结日 */
    @Test
    void finalizedPointsSkipCurrentNewYorkPlaceholderRow() {
        EtfFlowScraper scraper = new EtfFlowScraper(null);
        List<EtfFlowScraper.EtfFlowPoint> raw = scraper.parseAll(currentNewYorkDayPlaceholderHtml());
        List<EtfFlowScraper.EtfFlowPoint> finalized =
                scraper.finalizedPoints(raw, LocalDate.of(2026, 6, 10));

        assertEquals(LocalDate.of(2026, 6, 9), raw.get(raw.size() - 1).date());
        assertEquals(new BigDecimal("0.0"), raw.get(raw.size() - 1).totalFlowUsdMillion());
        assertEquals(LocalDate.of(2026, 6, 8), finalized.get(finalized.size() - 1).date());
        assertEquals(new BigDecimal("-91.4"), finalized.get(finalized.size() - 1).totalFlowUsdMillion());
    }

    private String sampleHtml() throws Exception {
        return new String(
                getClass().getResourceAsStream("/farside/sample-20260424.html").readAllBytes(),
                StandardCharsets.UTF_8);
    }

    private String currentNewYorkDayPlaceholderHtml() {
        return """
                <!doctype html>
                <html lang="en">
                <body>
                <table>
                    <tr>
                        <th>Date</th>
                        <th>IBIT</th>
                        <th>FBTC</th>
                        <th>Total</th>
                    </tr>
                    <tr>
                        <td>08 Jun 2026</td>
                        <td>(232.9)</td>
                        <td>59.4</td>
                        <td>(91.4)</td>
                    </tr>
                    <tr>
                        <td>09 Jun 2026</td>
                        <td>-</td>
                        <td>0.0</td>
                        <td>0.0</td>
                    </tr>
                </table>
                </body>
                </html>
                """;
    }
}
