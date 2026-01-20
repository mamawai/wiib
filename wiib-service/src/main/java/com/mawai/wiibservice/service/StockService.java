package com.mawai.wiibservice.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mawai.wiibcommon.dto.StockDTO;
import com.mawai.wiibcommon.entity.Stock;

import java.util.List;

/**
 * 股票服务接口
 */
public interface StockService extends IService<Stock> {

    /**
     * 根据股票代码查找
     * @param code 股票代码
     * @return 股票实体
     */
    Stock findByCode(String code);

    /**
     * 获取股票详情（含公司信息）
     * @param code 股票代码
     * @return 股票DTO
     */
    StockDTO getStockDetail(String code);

    /**
     * 获取所有股票列表
     * @return 股票列表
     */
    List<StockDTO> listAllStocks();

    /**
     * 获取涨幅榜
     * @param limit 数量限制
     * @return 涨幅榜
     */
    List<StockDTO> getTopGainers(int limit);

    /**
     * 获取跌幅榜
     * @param limit 数量限制
     * @return 跌幅榜
     */
    List<StockDTO> getTopLosers(int limit);
}
