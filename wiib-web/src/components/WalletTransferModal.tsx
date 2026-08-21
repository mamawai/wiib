import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { walletApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { useToast } from './ui/use-toast';
import { Button } from './ui/button';
import { Input } from './ui/input';
import { Dialog, DialogContent, DialogFooter, DialogHeader } from './ui/dialog';
import { ArrowLeft, ArrowRight, Gamepad2, Wallet } from 'lucide-react';
import { fmtNum } from '../lib/utils';
import { formatCoinPrice } from '../lib/coinConfig';
import type { WalletTransferPreview } from '../types';

type Direction = 'TO_GAME' | 'TO_BALANCE';

interface Props {
  open: boolean;
  onClose: () => void;
  /** 划转成功后页面自己的余额刷新（如 Mines/扑克的 status），user store 已由弹窗内部刷 */
  onSuccess?: () => void;
}

/** 余额钱包 ⇌ 游戏钱包 双向划转弹窗，各页面共用 */
export function WalletTransferModal({ open, onClose, onSuccess }: Props) {
  const { t } = useTranslation(['trade', 'common']);
  const user = useUserStore(s => s.user);
  const fetchUser = useUserStore(s => s.fetchUser);
  const { toast } = useToast();
  const [direction, setDirection] = useState<Direction>('TO_GAME');
  const [amount, setAmount] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [preview, setPreview] = useState<WalletTransferPreview | null>(null);

  // 打开时刷一次用户：刚玩完游戏时 store 里的 gameBalance 是旧的
  useEffect(() => {
    if (open) void fetchUser();
  }, [open, fetchUser]);

  const balance = user?.balance ?? 0;
  const gameBalance = user?.gameBalance ?? 0;
  const toGame = direction === 'TO_GAME';
  const sourceBalance = toGame ? balance : gameBalance;
  const amt = parseFloat(amount) || 0;
  // 后端 1% 手续费：转出全额扣，到账 = 金额 × 0.99
  const FEE_RATE = 0.01;
  const fee = Math.round(amt * FEE_RATE * 100) / 100;
  const receiveAmt = Math.round(amt * (1 - FEE_RATE) * 100) / 100;

  // 余额→游戏 才要预检：可转额度被全仓占用压低时给出上限和强平价影响，防抖 400ms 问后端
  useEffect(() => {
    if (!open || !toGame || amt <= 0) { setPreview(null); return; }
    let stale = false;
    const timer = window.setTimeout(() => {
      walletApi.transferPreview('TO_GAME', amt)
        .then(p => { if (!stale) setPreview(p); })
        .catch(() => { if (!stale) setPreview(null); });
    }, 400);
    return () => { stale = true; window.clearTimeout(timer); };
  }, [open, toGame, amt]);

  // allowed=false 时禁提交，preview 还没回来不拦（后端最终还会兜底校验）
  const transferBlocked = toGame && preview != null && preview.restricted && !preview.allowed;

  // 目标钱包名：toast、方向行、提交按钮共用一份
  const targetWallet = toGame ? t('wallet.gameWallet') : t('wallet.balanceWallet');

  const handleSubmit = async () => {
    if (submitting || amt <= 0) return;
    setSubmitting(true);
    try {
      await walletApi.transfer(direction, amt);
      toast(t('toast.transferOk', { amount: fmtNum(amt), received: fmtNum(receiveAmt), target: targetWallet }), 'success');
      setAmount('');
      await fetchUser();
      onSuccess?.();
      onClose();
    } catch (e: unknown) {
      toast((e as Error).message || t('toast.transferFailed'), 'error');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog open={open} onClose={onClose}>
      <DialogHeader>
        <h2 className="text-lg font-bold">{t('wallet.title')}</h2>
      </DialogHeader>
      <DialogContent>
        <div className="space-y-4">
          {/* 两个钱包 + 中间方向切换（转出/转入标签跟随方向翻转，按钮带"换向"提示） */}
          <div className="flex items-stretch gap-2">
            <div className="relative flex-1 rounded-md border border-border bg-card-2 p-3 text-center">
              <span className={`absolute top-1.5 right-1.5 text-[9px] font-bold px-1.5 py-0.5 rounded-full ${toGame ? 'bg-primary/10 text-primary' : 'bg-gain/10 text-gain'}`}>
                {toGame ? t('wallet.out') : t('wallet.in')}
              </span>
              <div className="text-xs text-muted-foreground flex items-center justify-center gap-1">
                <Wallet className="w-3 h-3" /> {t('wallet.balanceWallet')}
              </div>
              <div className="text-base font-bold tabular-nums mt-1">{fmtNum(balance)}</div>
            </div>
            <button
              onClick={() => setDirection(d => d === 'TO_GAME' ? 'TO_BALANCE' : 'TO_GAME')}
              className="self-center px-2 py-1.5 rounded-full border border-border bg-card hover:bg-surface-hover text-primary transition-colors flex flex-col items-center gap-0.5"
              title={t('wallet.switchDirTitle')}
              aria-label={t('wallet.switchDir')}
            >
              {toGame ? <ArrowRight className="w-4 h-4" /> : <ArrowLeft className="w-4 h-4" />}
              <span className="text-[9px] font-bold leading-none">{t('wallet.flip')}</span>
            </button>
            <div className="relative flex-1 rounded-md border border-border bg-card-2 p-3 text-center">
              <span className={`absolute top-1.5 right-1.5 text-[9px] font-bold px-1.5 py-0.5 rounded-full ${toGame ? 'bg-gain/10 text-gain' : 'bg-primary/10 text-primary'}`}>
                {toGame ? t('wallet.in') : t('wallet.out')}
              </span>
              <div className="text-xs text-muted-foreground flex items-center justify-center gap-1">
                <Gamepad2 className="w-3 h-3" /> {t('wallet.gameWallet')}
              </div>
              <div className="text-base font-bold tabular-nums mt-1">{fmtNum(gameBalance)}</div>
            </div>
          </div>

          <div className="text-xs text-muted-foreground text-center">
            {toGame
              ? `${t('wallet.balanceWallet')} → ${t('wallet.gameWallet')}`
              : `${t('wallet.gameWallet')} → ${t('wallet.balanceWallet')}`}
          </div>

          {/* 金额输入 + 全部（全部=源钱包全额，手续费从转出额里扣，不预留） */}
          <div className="flex gap-2">
            <Input
              type="number"
              min={0}
              placeholder={t('wallet.amountPlaceholder')}
              value={amount}
              onChange={e => setAmount(e.target.value)}
              className="flex-1 text-right font-mono tabular-nums"
            />
            <Button
              variant="outline"
              size="sm"
              className="h-11 px-4"
              onClick={() => setAmount(String(Math.floor(sourceBalance * 100) / 100))}
            >
              {t('wallet.max')}
            </Button>
          </div>

          {amt > 0 && (
            <div className="text-xs text-muted-foreground text-center tabular-nums">
              {t('wallet.feeLine', { fee: fee.toFixed(2), received: receiveAmt.toFixed(2) })}
            </div>
          )}

          {/* 全仓占用预检：restricted=false（可转=全部余额）时什么都不显示 */}
          {toGame && preview?.restricted && (
            preview.allowed ? (
              <div className="rounded-md border border-border bg-card-2 p-3 space-y-1.5 text-xs">
                <div className="flex justify-between">
                  <span className="text-muted-foreground">{t('wallet.equityAfter')}</span>
                  <span className="font-mono tabular-nums">{fmtNum(preview.equityAfter)}</span>
                </div>
                {preview.positions?.map(p => (
                  <div key={p.positionId} className="flex justify-between">
                    <span className="text-muted-foreground">
                      {t('wallet.newLiq', {
                        symbol: p.symbol,
                        dir: p.side === 'LONG' ? t('sideShort.long') : t('sideShort.short'),
                      })}
                    </span>
                    <span className="font-mono tabular-nums text-yellow-500">
                      {p.estLiqPrice > 0 ? formatCoinPrice(p.symbol, p.estLiqPrice) : 'N/A'}
                    </span>
                  </div>
                ))}
                {preview.maxTransferable != null && (
                  <div className="flex justify-between items-center pt-1 border-t border-border/40">
                    <span className="text-muted-foreground">{t('wallet.maxTransferable')}</span>
                    <button
                      type="button"
                      className="font-mono tabular-nums text-primary hover:underline underline-offset-2"
                      onClick={() => setAmount(String(preview.maxTransferable))}
                    >
                      {fmtNum(preview.maxTransferable)}
                    </button>
                  </div>
                )}
              </div>
            ) : (
              <div className="rounded-md border border-border bg-card-2 p-3 text-xs text-loss">
                {t('wallet.overLimit')}{' '}
                <button
                  type="button"
                  className="font-mono tabular-nums text-primary hover:underline underline-offset-2"
                  onClick={() => setAmount(String(preview.maxTransferable ?? 0))}
                >
                  {fmtNum(preview.maxTransferable ?? 0)}
                </button>
              </div>
            )
          )}
        </div>
      </DialogContent>
      <DialogFooter>
        <Button variant="ghost" size="sm" onClick={onClose}>{t('common:cancel')}</Button>
        <Button
          size="sm"
          onClick={handleSubmit}
          disabled={submitting || amt <= 0 || amt > sourceBalance || transferBlocked}
        >
          {submitting ? t('wallet.submitting') : t('wallet.transferTo', { target: targetWallet })}
        </Button>
      </DialogFooter>
    </Dialog>
  );
}
