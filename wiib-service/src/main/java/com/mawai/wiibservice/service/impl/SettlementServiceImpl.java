package com.mawai.wiibservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mawai.wiibcommon.entity.Settlement;
import com.mawai.wiibservice.mapper.SettlementMapper;
import com.mawai.wiibservice.service.SettlementService;
import com.mawai.wiibservice.service.UserService;
import com.mawai.wiibservice.util.RedisLockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementServiceImpl extends ServiceImpl<SettlementMapper, Settlement> implements SettlementService {

    private final UserService userService;
    private final RedisLockUtil redisLockUtil;

    @Override
    public void createSettlement(Long userId, Long orderId, BigDecimal amount) {
        Settlement settlement = new Settlement();
        settlement.setUserId(userId);
        settlement.setOrderId(orderId);
        settlement.setAmount(amount);
        settlement.setSettleDate(LocalDate.now().plusDays(1));
        settlement.setStatus("PENDING");
        baseMapper.insert(settlement);
        log.info("创建待结算 userId={} orderId={} amount={} settleDate={}",
                userId, orderId, amount, settlement.getSettleDate());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void processSettlements() {
        LambdaQueryWrapper<Settlement> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Settlement::getStatus, "PENDING")
                .le(Settlement::getSettleDate, LocalDate.now());

        List<Settlement> pendingList = baseMapper.selectList(wrapper);
        if (pendingList.isEmpty()) return;

        for (Settlement s : pendingList) {
            String lockKey = "settlement:process:" + s.getId();
            String lockValue = redisLockUtil.tryLock(lockKey, 30);
            if (lockValue == null) continue;

            try {
                Settlement fresh = baseMapper.selectById(s.getId());
                if (!"PENDING".equals(fresh.getStatus())) continue;

                userService.updateBalance(fresh.getUserId(), fresh.getAmount());
                fresh.setStatus("SETTLED");
                baseMapper.updateById(fresh);
                log.info("结算完成 settlementId={} userId={} amount={}",
                        fresh.getId(), fresh.getUserId(), fresh.getAmount());
            } catch (Exception e) {
                log.error("结算失败 settlementId={}", s.getId(), e);
            } finally {
                redisLockUtil.unlock(lockKey, lockValue);
            }
        }
    }

    @Override
    public List<Settlement> getPendingSettlements(Long userId) {
        LambdaQueryWrapper<Settlement> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Settlement::getUserId, userId)
                .eq(Settlement::getStatus, "PENDING")
                .orderByAsc(Settlement::getSettleDate);
        return baseMapper.selectList(wrapper);
    }

    @Override
    public BigDecimal getPendingAmount(Long userId) {
        List<Settlement> list = getPendingSettlements(userId);
        return list.stream()
                .map(Settlement::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
