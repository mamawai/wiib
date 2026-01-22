package com.mawai.wiibservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mawai.wiibcommon.dto.UserDTO;
import com.mawai.wiibcommon.entity.Settlement;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibservice.mapper.UserMapper;
import com.mawai.wiibservice.service.PositionService;
import com.mawai.wiibservice.service.SettlementService;
import com.mawai.wiibservice.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 用户服务实现
 * 使用数据库原子操作保证并发安全
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final PositionService positionService;
    private final SettlementService settlementService;

    @Value("${trading.initial-balance:100000}")
    private BigDecimal initialBalance;

    @Override
    public User findByLinuxDoId(String linuxDoId) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getLinuxDoId, linuxDoId);
        return baseMapper.selectOne(wrapper);
    }

    @Override
    public UserDTO getUserPortfolio(Long userId) {
        User user = baseMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        BigDecimal marketValue = positionService.calculateTotalMarketValue(userId);
        BigDecimal frozenBalance = user.getFrozenBalance() != null ? user.getFrozenBalance() : BigDecimal.ZERO;
        BigDecimal marginLoanPrincipal = user.getMarginLoanPrincipal() != null ? user.getMarginLoanPrincipal() : BigDecimal.ZERO;
        BigDecimal marginInterestAccrued = user.getMarginInterestAccrued() != null ? user.getMarginInterestAccrued() : BigDecimal.ZERO;
        
        // 计算待结算金额
        BigDecimal pendingSettlement = settlementService.getPendingSettlements(userId).stream()
                .map(Settlement::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalAssets = user.getBalance()
                .add(frozenBalance)
                .add(marketValue)
                .add(pendingSettlement)
                .subtract(marginLoanPrincipal)
                .subtract(marginInterestAccrued);

        return getUserDTO(totalAssets, user, frozenBalance, marketValue, pendingSettlement, marginLoanPrincipal, marginInterestAccrued);
    }

    private UserDTO getUserDTO(BigDecimal totalAssets, User user,
                               BigDecimal frozenBalance,
                               BigDecimal positionMarketValue,
                               BigDecimal pendingSettlement,
                               BigDecimal marginLoanPrincipal,
                               BigDecimal marginInterestAccrued) {
        BigDecimal profit = totalAssets.subtract(initialBalance);
        BigDecimal profitPct = profit.divide(initialBalance, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setAvatar(user.getAvatar());
        dto.setBalance(user.getBalance());
        dto.setFrozenBalance(frozenBalance);
        dto.setPositionMarketValue(positionMarketValue);
        dto.setPendingSettlement(pendingSettlement);
        dto.setMarginLoanPrincipal(marginLoanPrincipal);
        dto.setMarginInterestAccrued(marginInterestAccrued);
        dto.setBankrupt(Boolean.TRUE.equals(user.getIsBankrupt()));
        dto.setBankruptCount(user.getBankruptCount() != null ? user.getBankruptCount() : 0);
        dto.setBankruptResetDate(user.getBankruptResetDate());
        dto.setTotalAssets(totalAssets);
        dto.setProfit(profit);
        dto.setProfitPct(profitPct);
        return dto;
    }

    @Override
    public void updateBalance(Long userId, BigDecimal amount) {
        int affected = baseMapper.atomicUpdateBalance(userId, amount);
        if (affected == 0) {
            if (baseMapper.selectById(userId) == null) {
                throw new BizException(ErrorCode.USER_NOT_FOUND);
            }
            throw new BizException(ErrorCode.BALANCE_NOT_ENOUGH);
        }
        log.info("用户{}余额更新: {}", userId, amount);
        baseMapper.selectById(userId);
    }

    @Override
    public void freezeBalance(Long userId, BigDecimal amount) {
        int affected = baseMapper.atomicFreezeBalance(userId, amount);
        if (affected == 0) {
            if (baseMapper.selectById(userId) == null) {
                throw new BizException(ErrorCode.USER_NOT_FOUND);
            }
            throw new BizException(ErrorCode.BALANCE_NOT_ENOUGH);
        }
        log.info("用户{}冻结余额: {}", userId, amount);
        baseMapper.selectById(userId);
    }

    @Override
    public void unfreezeBalance(Long userId, BigDecimal amount) {
        int affected = baseMapper.atomicUnfreezeBalance(userId, amount);
        if (affected == 0) {
            if (baseMapper.selectById(userId) == null) {
                throw new BizException(ErrorCode.USER_NOT_FOUND);
            }
            throw new BizException(ErrorCode.FROZEN_BALANCE_NOT_ENOUGH);
        }
        log.info("用户{}解冻余额: {}", userId, amount);
        baseMapper.selectById(userId);
    }

    @Override
    public void deductFrozenBalance(Long userId, BigDecimal amount) {
        int affected = baseMapper.atomicDeductFrozenBalance(userId, amount);
        if (affected == 0) {
            if (baseMapper.selectById(userId) == null) {
                throw new BizException(ErrorCode.USER_NOT_FOUND);
            }
            throw new BizException(ErrorCode.FROZEN_BALANCE_NOT_ENOUGH);
        }
        log.info("用户{}扣除冻结余额: {}", userId, amount);
    }
}
