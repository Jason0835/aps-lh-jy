package com.zlt.aps.lh.controller;

import com.zlt.aps.lh.api.domain.dto.LhScheduleRequestDTO;
import com.zlt.aps.lh.api.domain.dto.LhScheduleResponseDTO;
import com.zlt.aps.lh.service.ILhScheduleService;
import com.zlt.aps.lh.util.LhScheduleTimeUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 硫化排程对外接口控制器。
 *
 * <p>业务定位：</p>
 * <ul>
 *   <li>作为硫化排程接口入口，接收排程执行和排程发布请求；</li>
 *   <li>不承载排程算法，仅做请求日志记录并委托 {@link ILhScheduleService}；</li>
 *   <li>排程执行请求最终进入服务层构建 {@code LhScheduleContext}，再由模板链路执行 S4.1～S4.6；</li>
 *   <li>发布请求只按批次号发布已生成的硫化排程结果。</li>
 * </ul>
 *
 * <p>注意：该类不应加入 SKU 排序、机台选择、换模或班次分配等业务规则。</p>
 *
 * @author APS
 */
@Api(tags = "硫化排程接口")
@Slf4j
@RestController
@RequestMapping("/lhScheduleResult")
public class LhScheduleResultController {

    @Resource
    private ILhScheduleService lhScheduleService;

    /**
     * 执行自动排程。
     *
     * <p>入口只记录工厂和排程日期，实际业务由 {@link ILhScheduleService#executeSchedule(LhScheduleRequestDTO)}
     * 负责，包括排程锁、参数快照、基础数据初始化、续作、新增排产和结果保存。</p>
     *
     * @param request 排程请求参数
     * @return 排程响应结果
     */
    @PostMapping("/execute")
    @ApiOperation("执行自动排程")
    public LhScheduleResponseDTO executeSchedule(@RequestBody LhScheduleRequestDTO request) {
        log.info("收到排程请求, 工厂: {}, 日期: {}",
                request.getFactoryCode(), LhScheduleTimeUtil.formatDate(request.getScheduleDate()));
        return lhScheduleService.executeSchedule(request);
    }

    /**
     * 发布排程结果到MES。
     *
     * <p>按批次号发布已落库的排程结果，不重新计算排程量，也不改变机台、模具和班次分配。</p>
     *
     * @param batchNo 批次号
     * @return 发布响应结果
     */
    @PostMapping("/publish/{batchNo}")
    @ApiOperation("发布排程结果到MES")
    public LhScheduleResponseDTO publishSchedule(@PathVariable("batchNo") String batchNo) {
        log.info("收到发布请求, 批次号: {}", batchNo);
        return lhScheduleService.publishSchedule(batchNo);
    }
}
