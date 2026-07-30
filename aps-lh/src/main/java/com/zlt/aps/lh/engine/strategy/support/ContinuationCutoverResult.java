package com.zlt.aps.lh.engine.strategy.support;

import com.zlt.aps.lh.api.domain.entity.LhScheduleResult;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 续作机台按指定下机时间截断后的运行态结果。
 *
 * @author APS
 */
@Data
public class ContinuationCutoverResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 被截断的续作结果引用；字段已经刷新为保留量。 */
    private List<LhScheduleResult> retainedResultList = new ArrayList<LhScheduleResult>(2);
    /** 各业务日被截断并恢复到 B 日计划账本的数量。 */
    private Map<LocalDate, Integer> removedQtyByDate = new LinkedHashMap<LocalDate, Integer>(4);
    /** 被截断并需要由 B 新机台完整承接的总量。 */
    private int removedQty;
}
