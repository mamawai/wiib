import * as echarts from 'echarts';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { cn } from '../lib/utils';
import type { AssetSnapshot } from '../types';
import { useIsDark } from '../hooks/useIsDark';

interface Props {
  data: AssetSnapshot[];
}

// 五分类收益曲线：crypto 含币合约，大宗商品含金/油合约
// 常量在组件外拿不到 t，存词表 key，画图时再查——存翻译结果会在模块加载那一刻定死，切语言不跟着变
const CUMULATIVE_CONFIG = [
  { key: 'profit', nameKey: 'cat.totalProfit', color: '#635bff' },
  { key: 'cryptoProfit', nameKey: 'cat.crypto', color: '#f97316' },
  { key: 'commodityProfit', nameKey: 'cat.commodity', color: '#eab308' },
  { key: 'bstockProfit', nameKey: 'cat.bstock', color: '#0ea5e9' },
  { key: 'predictionProfit', nameKey: 'cat.prediction', color: '#a855f7' },
  { key: 'gameProfit', nameKey: 'cat.game', color: '#ef4444' },
] as const;

const DAILY_CONFIG = [
  { key: 'dailyProfit', nameKey: 'cat.dailyProfit', color: '#635bff' },
  { key: 'dailyCryptoProfit', nameKey: 'cat.crypto', color: '#f97316' },
  { key: 'dailyCommodityProfit', nameKey: 'cat.commodity', color: '#eab308' },
  { key: 'dailyBstockProfit', nameKey: 'cat.bstock', color: '#0ea5e9' },
  { key: 'dailyPredictionProfit', nameKey: 'cat.prediction', color: '#a855f7' },
  { key: 'dailyGameProfit', nameKey: 'cat.game', color: '#ef4444' },
] as const;

export function ProfitChart({ data }: Props) {
  const chartRef = useRef<HTMLDivElement>(null);
  const [mode, setMode] = useState<'cumulative' | 'daily'>('cumulative');
  const [dailyRange, setDailyRange] = useState<7 | 14 | 30>(7);
  const isDark = useIsDark();
  const { t, i18n } = useTranslation('portfolio');

  const filteredData = mode === 'daily' ? data.slice(-dailyRange) : data;

  useEffect(() => {
    if (!chartRef.current || filteredData.length === 0) return;
    const chart = echarts.init(chartRef.current, isDark ? 'dark' : 'light');

    const config = mode === 'daily' ? DAILY_CONFIG : CUMULATIVE_CONFIG;
    const dates = filteredData.map(d => d.date);
    const textColor = isDark ? '#878b96' : '#71737b';
    const gainColor = isDark ? '#0abf95' : '#089981';
    const lossColor = isDark ? '#ff5a68' : '#f23645';

    const series: echarts.SeriesOption[] = config.map(cfg => ({
      name: t(cfg.nameKey),
      type: 'line',
      data: filteredData.map(d => d[cfg.key as keyof AssetSnapshot] as number ?? 0),
      smooth: true,
      symbol: 'circle',
      symbolSize: filteredData.length <= 7 ? 6 : 0,
      lineStyle: {
        width: cfg.key === 'profit' || cfg.key === 'dailyProfit' ? 2.5 : 1.5,
      },
      itemStyle: { color: cfg.color },
      ...(cfg.key === 'profit' || cfg.key === 'dailyProfit' ? {
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: isDark ? 'rgba(99,91,255,0.25)' : 'rgba(99,91,255,0.15)' },
            { offset: 1, color: 'rgba(99,91,255,0)' },
          ]),
        },
      } : {}),
      emphasis: { focus: 'series' as const },
    }));

    chart.setOption({
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'axis',
        backgroundColor: isDark ? '#13151a' : '#FFFFFF',
        borderColor: isDark ? '#23262e' : '#e4e4df',
        textStyle: { color: isDark ? '#eceef0' : '#17181a', fontSize: 12 },
        formatter: (params: { axisValue: string; value: number; marker: string; seriesName: string }[]) => {
          const date = params[0]?.axisValue ?? '';
          let html = `<div style="font-weight:600;margin-bottom:4px">${date}</div>`;
          for (const p of params) {
            const v = (p.value as number).toFixed(2);
            const sign = p.value >= 0 ? '+' : '';
            html += `<div style="display:flex;align-items:center;gap:6px;margin:2px 0">
              ${p.marker}<span>${p.seriesName}</span>
              <span style="margin-left:auto;font-weight:600;color:${p.value >= 0 ? gainColor : lossColor}">${sign}${v}</span>
            </div>`;
          }
          return html;
        },
      },
      legend: {
        bottom: 0,
        textStyle: { color: textColor, fontSize: 10 },
        itemWidth: 10,
        itemHeight: 2,
        itemGap: 6,
        icon: 'roundRect',
        type: 'scroll',
      },
      grid: { left: 8, right: 8, top: 16, bottom: 40, containLabel: true },
      xAxis: {
        type: 'category',
        data: dates,
        axisLabel: {
          color: textColor,
          fontSize: 9,
          formatter: (v: string) => v.substring(5),
        },
        axisLine: { lineStyle: { color: isDark ? '#23262e' : '#e4e4df' } },
        axisTick: { show: false },
      },
      yAxis: {
        type: 'value',
        splitLine: { lineStyle: { color: isDark ? '#181b21' : '#f1f1ee', type: 'dashed' } },
        axisLabel: { color: textColor, fontSize: 9 },
      },
      series,
    });

    const onResize = () => chart.resize();
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      chart.dispose();
    };
    // 依赖里必须带 i18n.language：少了它切语言后 option 不重算，图例还是旧文案
  }, [filteredData, mode, isDark, t, i18n.language]);

  if (data.length === 0) {
    return (
      <div className="w-full h-48 sm:h-56 flex items-center justify-center text-sm text-muted-foreground">
        {t('chart.noHistory')}
      </div>
    );
  }

  return (
    <div className="w-full">
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-2 mb-2">
        <div className="flex gap-1">
          {mode === 'daily' && ([7, 14, 30] as const).map(d => (
            <button
              key={d}
              onClick={() => setDailyRange(d)}
              className={cn(
                "px-3 py-1.5 rounded-lg text-xs sm:text-[10px] font-medium transition-colors min-w-[44px]",
                dailyRange === d ? "bg-primary/15 text-primary" : "text-muted-foreground hover:text-foreground bg-muted/50"
              )}
            >
              {t('chart.range', { days: d })}
            </button>
          ))}
        </div>
        <div className="flex gap-1">
          <button
            onClick={() => setMode('cumulative')}
            className={cn(
              "px-3 py-1.5 rounded-lg text-xs sm:text-[10px] font-medium transition-colors min-w-[44px]",
              mode === 'cumulative' ? "bg-primary/15 text-primary" : "text-muted-foreground hover:text-foreground bg-muted/50"
            )}
          >
            {t('chart.cumulative')}
          </button>
          <button
            onClick={() => setMode('daily')}
            className={cn(
              "px-3 py-1.5 rounded-lg text-xs sm:text-[10px] font-medium transition-colors min-w-[44px]",
              mode === 'daily' ? "bg-primary/15 text-primary" : "text-muted-foreground hover:text-foreground bg-muted/50"
            )}
          >
            {t('cat.dailyProfit')}
          </button>
        </div>
      </div>
      <div ref={chartRef} className="w-full h-56 sm:h-72" />
    </div>
  );
}
