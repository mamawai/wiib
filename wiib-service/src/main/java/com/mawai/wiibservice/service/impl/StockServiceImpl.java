package com.mawai.wiibservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mawai.wiibcommon.dto.StockDTO;
import com.mawai.wiibcommon.entity.Company;
import com.mawai.wiibcommon.entity.Stock;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibservice.mapper.CompanyMapper;
import com.mawai.wiibservice.mapper.StockMapper;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.StockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 股票服务实现
 * Stock实体只存静态数据，实时数据从Redis获取
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockServiceImpl extends ServiceImpl<StockMapper, Stock> implements StockService {

    private final CompanyMapper companyMapper;
    private final CacheService cacheService;

    private static final String STOCK_LIST_CACHE_KEY = "stock:list:all";
    private static final String STOCK_DETAIL_CACHE_KEY = "stock:detail:";
    private static final int CACHE_EXPIRE_SECONDS = 60;

    /**
     * 根据股票代码查找
     */
    @Override
    public Stock findByCode(String code) {
        LambdaQueryWrapper<Stock> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Stock::getCode, code);
        return baseMapper.selectOne(wrapper);
    }

    /**
     * 获取股票详情（含公司信息，带缓存）
     */
    @Override
    public StockDTO getStockDetail(String code) {
        String cacheKey = STOCK_DETAIL_CACHE_KEY + code;
        StockDTO cached = cacheService.getObject(cacheKey);
        if (cached != null) {
            return cached;
        }

        Stock stock = findByCode(code);
        if (stock == null) {
            throw new BizException(ErrorCode.STOCK_NOT_FOUND);
        }

        Company company = companyMapper.selectById(stock.getCompanyId());
        StockDTO dto = buildStockDTO(stock, company);

        cacheService.setObject(cacheKey, dto, CACHE_EXPIRE_SECONDS, TimeUnit.SECONDS);
        return dto;
    }

    /**
     * 获取所有股票列表（带缓存）
     */
    @Override
    public List<StockDTO> listAllStocks() {
        List<StockDTO> cached = cacheService.getList(STOCK_LIST_CACHE_KEY);
        if (cached != null) {
            return cached;
        }

        List<Stock> stocks = baseMapper.selectList(null);
        List<StockDTO> dtos = stocks.stream()
                .map(stock -> {
                    Company company = companyMapper.selectById(stock.getCompanyId());
                    return buildStockDTO(stock, company);
                })
                .collect(Collectors.toList());

        cacheService.setObject(STOCK_LIST_CACHE_KEY, dtos, CACHE_EXPIRE_SECONDS, TimeUnit.SECONDS);
        return dtos;
    }

    /**
     * 分页查询股票列表
     */
    public Page<StockDTO> listStocksByPage(int pageNum, int pageSize) {
        Page<Stock> page = new Page<>(pageNum, pageSize);
        baseMapper.selectPage(page, null);

        Page<StockDTO> dtoPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        List<StockDTO> dtos = page.getRecords().stream()
                .map(stock -> {
                    Company company = companyMapper.selectById(stock.getCompanyId());
                    return buildStockDTO(stock, company);
                })
                .sorted(Comparator.comparing(StockDTO::getChangePct, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
        dtoPage.setRecords(dtos);

        return dtoPage;
    }

    /**
     * 获取涨幅榜（按涨跌幅降序）
     */
    @Override
    public List<StockDTO> getTopGainers(int limit) {
        List<Stock> stocks = baseMapper.selectList(null);
        return stocks.stream()
                .map(stock -> {
                    Company company = companyMapper.selectById(stock.getCompanyId());
                    return buildStockDTO(stock, company);
                })
                .filter(dto -> dto.getChangePct() != null)
                .sorted(Comparator.comparing(StockDTO::getChangePct).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 获取跌幅榜（按涨跌幅升序）
     */
    @Override
    public List<StockDTO> getTopLosers(int limit) {
        List<Stock> stocks = baseMapper.selectList(null);
        return stocks.stream()
                .map(stock -> {
                    Company company = companyMapper.selectById(stock.getCompanyId());
                    return buildStockDTO(stock, company);
                })
                .filter(dto -> dto.getChangePct() != null)
                .sorted(Comparator.comparing(StockDTO::getChangePct))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 构建StockDTO（静态数据+Redis实时数据）
     */
    private StockDTO buildStockDTO(Stock stock, Company company) {
        StockDTO dto = new StockDTO();
        dto.setId(stock.getId());
        dto.setCode(stock.getCode());
        dto.setName(stock.getName());
        dto.setPrevClose(stock.getPrevClose());
        dto.setVolume(stock.getVolume());
        dto.setTurnover(stock.getTurnover());

        // 从Redis获取实时数据
        Map<String, BigDecimal> quote = cacheService.getDailyQuote(stock.getId());
        if (quote != null) {
            dto.setPrice(quote.get("last"));
            dto.setOpenPrice(quote.get("open"));
            dto.setHighPrice(quote.get("high"));
            dto.setLowPrice(quote.get("low"));

            // 计算涨跌
            BigDecimal price = quote.get("last");
            if (price != null && stock.getPrevClose() != null && stock.getPrevClose().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal change = price.subtract(stock.getPrevClose());
                BigDecimal changePct = change.divide(stock.getPrevClose(), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
                dto.setChange(change);
                dto.setChangePct(changePct);
            }
        } else {
            // 无实时数据时用开盘价（AI预生成）
            dto.setPrice(stock.getOpen() != null ? stock.getOpen() : stock.getPrevClose());
        }

        // 公司信息
        if (company != null) {
            dto.setIndustry(company.getIndustry());
            dto.setMarketCap(company.getMarketCap());
            dto.setPeRatio(company.getPeRatio());
            dto.setCompanyDesc(company.getDescription());
        }

        return dto;
    }
}
