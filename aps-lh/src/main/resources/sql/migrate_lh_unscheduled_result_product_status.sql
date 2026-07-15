-- 硫化未排结果增加产品状态，支持同物料不同产品状态分别落库和归并。
ALTER TABLE T_LH_UNSCHEDULED_RESULT
    ADD COLUMN PRODUCT_STATUS varchar(30) DEFAULT NULL
        COMMENT '产品状态 X-试验示方 T-量试示方 S-正规示方'
        AFTER MATERIAL_CODE;
