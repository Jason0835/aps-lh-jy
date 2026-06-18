package com.zlt.aps.lh.util;

import com.zlt.aps.mp.api.domain.entity.MpMonthPlanStatistics;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 月计划结构统计 dayN 解析测试。
 */
class MonthPlanStatisticsDayUtilTest {

    @Test
    void resolveLhMachines_shouldParseNumberAndTextValue() {
        MpMonthPlanStatistics row = new MpMonthPlanStatistics();
        row.setStructureName("L1");
        row.setDay12("{\"lhMachines\":2}");
        row.setDay13("{\"lhMachines\":\"3\"}");

        assertEquals(2, MonthPlanStatisticsDayUtil.resolveLhMachines(row, LocalDate.of(2026, 6, 12)));
        assertEquals(3, MonthPlanStatisticsDayUtil.resolveLhMachines(row, LocalDate.of(2026, 6, 13)));
    }

    @Test
    void resolveLhMachines_shouldReturnZeroWhenDayJsonEmptyOrKeyMissing() {
        MpMonthPlanStatistics row = new MpMonthPlanStatistics();
        row.setStructureName("L1");
        row.setDay12(null);
        row.setDay13("{\"other\":3}");

        assertEquals(0, MonthPlanStatisticsDayUtil.resolveLhMachines(row, LocalDate.of(2026, 6, 12)));
        assertEquals(0, MonthPlanStatisticsDayUtil.resolveLhMachines(row, LocalDate.of(2026, 6, 13)));
    }

    @Test
    void resolveLhMachines_shouldThrowWhenDayJsonInvalid() {
        MpMonthPlanStatistics row = new MpMonthPlanStatistics();
        row.setStructureName("L1");
        row.setDay12("EmbryoCount:1,LhMachines:2,ChangeMould:0");

        assertThrows(IllegalArgumentException.class,
                () -> MonthPlanStatisticsDayUtil.resolveLhMachines(row, LocalDate.of(2026, 6, 12)));
    }
}
