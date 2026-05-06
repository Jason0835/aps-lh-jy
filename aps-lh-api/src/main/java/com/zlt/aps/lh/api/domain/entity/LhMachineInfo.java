package com.zlt.aps.lh.api.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ruoyi.common.core.annotation.Excel;
import com.ruoyi.common.core.web.domain.BaseEntity;
import com.zlt.common.annotation.ImportExcelValidated;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.io.Serializable;
import java.math.BigDecimal;


@ApiModel(value = "硫化机台信息对象", description = "硫化机台信息对象")
@Data
@TableName(value = "T_LH_MACHINE_INFO")
public class LhMachineInfo extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 6804706470628270367L;

    /** 分厂编号 */
    @ImportExcelValidated(required = true)
    @ApiModelProperty(value = "分厂编号", name = "factoryCode")
    @TableField(value = "FACTORY_CODE")
    @Excel(name = "ui.data.column.result.factoryCode", dictType = "biz_factory_name" ,sort = 10)
    private String factoryCode;

     /** 机台编号 */
    @Excel(name = "ui.data.column.info.machineCode",sort = 20)
    @ApiModelProperty(value = "机台编号", name = "machineCode")
    @ImportExcelValidated(required = true)
    @TableField(value = "MACHINE_CODE")
    private String machineCode;

    /** 机台名称 */
    @ImportExcelValidated(required = true)
    @Excel(name = "ui.data.column.info.machineName",sort = 30)
    @ApiModelProperty(value = "机台名称", name = "machineName")
    @TableField(value = "MACHINE_NAME")
    private String machineName;

    /** 寸口信息 */
//    @Excel(name = "ui.data.column.info.dimension",sort = 50)
    @ApiModelProperty(value = "寸口信息", name = "dimension")
    @TableField(value = "DIMENSION")
    private BigDecimal dimension;

    /** 向心机构，数据字典取值（如：0-无，1-有） */
    @ApiModelProperty(value = "向心机构，数据字典IS_HAVE取值", name = "centripetalMechanism")
    @TableField(value = "CENTRIPETAL_MECHANISM")
    private String centripetalMechanism;

    /** 生产寸口范围下限，单位：英寸 */
    @ImportExcelValidated(required = true, number = true, min = 0, max = 9999)
    @Excel(name = "ui.data.column.info.dimensionMinimum", cellType = Excel.ColumnType.NUMERIC, sort = 60)
    @ApiModelProperty(value = "生产寸口范围下限，单位：英寸", name = "dimensionMinimum")
    @TableField(value = "DIMENSION_MINIMUM")
    private BigDecimal dimensionMinimum;

    /** 生产寸口范围上限，单位：英寸 */
    @ImportExcelValidated(required = true, number = true, min = 1, max = 9999)
    @Excel(name = "ui.data.column.info.dimensionMaximum", cellType = Excel.ColumnType.NUMERIC, sort = 70)
    @ApiModelProperty(value = "生产寸口范围上限，单位：英寸", name = "dimensionMaximum")
    @TableField(value = "DIMENSION_MAXIMUM")
    private BigDecimal dimensionMaximum;

    /** 班制，如：三班制、两班制，对应数据字典 CLASS_SHIFT */
    @ApiModelProperty(value = "班制，如：三班制、两班制，对应数据字典 CLASS_SHIFT", name = "classShift")
    @TableField(value = "CLASS_SHIFT")
    private String classShift;

    /** 最大使用模具数量，范围 0-255 单双模*/
    @ImportExcelValidated(required = true, digits = true, min = 0, max = 255)
    @Excel(name = "ui.data.column.info.maxMoldNum", cellType = Excel.ColumnType.NUMERIC, sort = 80)
    @ApiModelProperty(value = "模台数 单双模", name = "maxMoldNum")
    @TableField(value = "MAX_MOLD_NUM")
    private Integer maxMoldNum;

    /** 生产定额，单班一次生产量，单位：条 */
    @ImportExcelValidated(required = true, digits = true, min = 0, max = 9999)
    @Excel(name = "ui.data.column.info.quota", cellType = Excel.ColumnType.NUMERIC, sort = 90)
    @ApiModelProperty(value = "生产定额，单班一次生产量，单位：条", name = "quota")
    @TableField(value = "QUOTA")
    private Integer quota;

    /** 开机班次，如：中班、夜班，对应数据字典 CLASS_NUM */
    @ApiModelProperty(value = "开机班次，如：中班、夜班，对应数据字典 CLASS_NUM", name = "openMachineClass")
    @TableField(value = "OPEN_MACHINE_CLASS")
    private String openMachineClass;

    /** 是否启用 */
    @Excel(name = "ui.data.column.info.status",sort = 100,dictType = "sys_enable_disable")
    @ImportExcelValidated(required = true)
    @ApiModelProperty(value = "是否启用，字典：sys_enable_disable", name = "status")
    @TableField(value = "STATUS")
    private String status;

    @Excel(name = "ui.data.column.info.machineType",sort = 40,dictType = "LH_MACHINE_TYPE")
    @ImportExcelValidated(required = true)
    @ApiModelProperty(value = "机台类型，字典：LH_MACHINE_TYPE", name = "machineType")
    @TableField(value = "MACHINE_TYPE")
    private String machineType;

    @ApiModelProperty(value = "模具关系", name = "moldRelationList")
    @TableField(value = "MOLD_RELATION_LIST")
    private String moldRelationList;

    @ImportExcelValidated(digits = true, min = 0, max = 9999)
    @Excel(name = "ui.data.column.info.machineOrder", cellType = Excel.ColumnType.NUMERIC, sort = 110)
    @ApiModelProperty(value = "机台顺序", name = "machineOrder")
    @TableField(value = "MACHINE_ORDER", updateStrategy = FieldStrategy.IGNORED, jdbcType = JdbcType.INTEGER)
    private Integer machineOrder;

    @Excel(name = "ui.data.column.info.remark", sort = 120)
    @ApiModelProperty(value = "备注", name = "remark")
    @TableField("REMARK")
    private String remark;

    /**
     * 厂家
     */
    @Excel(name = "ui.data.column.info.manufacturer", sort = 25)
    @ApiModelProperty(value = "厂家", name = "manufacturer")
    @TableField("MANUFACTURER")
    private String manufacturer;

    /**
     * 尺寸
     */
    @Excel(name = "ui.data.column.info.dimensionSize", sort = 50)
    @ApiModelProperty(value = "尺寸", name = "dimensionSize")
    @TableField("DIMENSION_SIZE")
    private String dimensionSize;

    /**
     * 热板直径，单位：mm
     */
    @Excel(name = "ui.data.column.info.hotPlateDiameter", sort = 51)
    @ApiModelProperty(value = "热板直径，单位：mm", name = "hotPlateDiameter")
    @TableField("HOT_PLATE_DIAMETER")
    private String hotPlateDiameter;

    /**
     * 模套型号，空值表示适配所有
     */
    @Excel(name = "ui.data.column.info.shellStandard", sort = 52)
    @ApiModelProperty(value = "模套型号", name = "shellStandard")
    @TableField("SHELL_STANDARD")
    private String shellStandard;

    /**
     * 支持19.5寸宽基 0-否，1-是
     */
    @Excel(name = "ui.data.column.info.support195WideBase", dictType = "biz_yes_no", sort = 75)
    @ApiModelProperty(value = "支持19.5寸宽基 0-否，1-是，字典：biz_yes_no", name = "support195WideBase")
    @TableField("SUPPORT195_WIDE_BASE")
    private String support195WideBase;

    /**
     * 支持22.5寸宽基 0-否，1-是
     */
    @Excel(name = "ui.data.column.info.support225WideBase", dictType = "biz_yes_no", sort = 77)
    @ApiModelProperty(value = "支持22.5寸宽基 0-否，1-是，字典：biz_yes_no", name = "support225WideBase")
    @TableField("SUPPORT225_WIDE_BASE")
    private String support225WideBase;

    /**
     * 支持芯片胎 0-否，1-是
     */
    @Excel(name = "ui.data.column.info.supportChipTire", dictType = "biz_yes_no", sort = 79)
    @ApiModelProperty(value = "支持芯片胎 0-否，1-是，字典：biz_yes_no", name = "supportChipTire")
    @TableField("SUPPORT_CHIP_TIRE")
    private String supportChipTire;

}
