import * as echarts from 'echarts';
import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import type { CategoryAverages } from '../types';
import { useIsDark } from '../hooks/useIsDark';

interface Props {
  userData: CategoryAverages;
}

// 五分类能力轴：与后端 CategoryAveragesDTO 一一对应。表里存词表 key，渲染时现查——
// 存成文案会在模块加载那一刻定死，切语言不跟着变
const INDICATORS = [
  { labelKey: 'radar.crypto', key: 'cryptoProfit' },
  { labelKey: 'radar.commodity', key: 'commodityProfit' },
  { labelKey: 'radar.bstock', key: 'bstockProfit' },
  { labelKey: 'radar.prediction', key: 'predictionProfit' },
  { labelKey: 'radar.game', key: 'gameProfit' },
];

export function RadarChart({ userData }: Props) {
  const chartRef = useRef<HTMLDivElement>(null);
  const chartInstanceRef = useRef<echarts.ECharts | null>(null);
  const isDark = useIsDark();
  const { t, i18n } = useTranslation('strategy');

  // 依赖带 i18n.language：切语言时整张图重建，legend/tooltip/轴名才跟着换
  useEffect(() => {
    if (!chartRef.current) return;
    const chart = echarts.init(chartRef.current, isDark ? 'dark' : 'light');
    chartInstanceRef.current = chart;

    const buildOption = (d: boolean) => {
      const userValues = INDICATORS.map(ind => userData[ind.key as keyof CategoryAverages] || 0);
      const avgVal = userValues.length ? userValues.reduce((a, b) => a + b, 0) / userValues.length : 0;
      return {
        backgroundColor: 'transparent',
        legend: {
          data: [t('radar.betterThan', { pct: Math.round(avgVal) })],
          bottom: 0,
          textStyle: { color: d ? '#878b96' : '#71737b', fontSize: 11 },
        },
        tooltip: {
          trigger: 'item',
          backgroundColor: d ? '#13151a' : '#fff',
          borderColor: d ? '#23262e' : '#e4e4df',
          textStyle: { color: d ? '#eceef0' : '#17181a', fontSize: 12 },
          formatter: (params: { value: number[] }) => {
            const vals = params.value;
            return INDICATORS.map((ind, i) =>
              `${t(ind.labelKey)}: <b>${t('radar.betterThan', { pct: Number(vals[i]).toFixed(2) })}</b>`).join('<br/>');
          },
        },
        radar: {
          indicator: INDICATORS.map(ind => ({
            name: t(ind.labelKey),
            max: 100,
          })),
          shape: 'polygon',
          splitNumber: 5,
          center: ['50%', '45%'],
          radius: '65%',
          axisName: {
            color: d ? '#878b96' : '#71737b',
            fontSize: 11,
          },
          splitLine: {
            lineStyle: { color: d ? '#23262e' : '#e4e4df' },
          },
          splitArea: {
            areaStyle: { color: d ? ['#13151a', '#181b21', '#13151a', '#181b21', '#13151a'] : ['#fafaf8', '#f1f1ee', '#fafaf8', '#f1f1ee', '#fafaf8'] },
          },
          axisLine: {
            lineStyle: { color: d ? '#23262e' : '#e4e4df' },
          },
        },
        series: [
          {
            type: 'radar',
            data: [
              {
                value: userValues,
                name: t('radar.betterThan', { pct: Math.round(avgVal) }),
                lineStyle: { color: '#635bff', width: 2 },
                areaStyle: { color: 'rgba(99, 91, 255, 0.3)' },
                itemStyle: { color: '#635bff' },
                symbol: 'circle',
                symbolSize: 6,
              },
            ],
          },
        ],
      };
    };

    chart.setOption(buildOption(isDark));

    const onResize = () => chartInstanceRef.current?.resize();
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      chartInstanceRef.current?.dispose();
    };
  }, [userData, isDark, t, i18n.language]);

  return (
    <div className="w-full">
      <div ref={chartRef} className="w-full h-72 sm:h-96" />
      {/* 五分类百分位速览：免 hover 直读，值=胜过多少其他用户 */}
      <div className="grid grid-cols-5 gap-1 mt-2 px-1">
        {INDICATORS.map(ind => {
          const v = Number(userData[ind.key as keyof CategoryAverages] || 0);
          return (
            <div key={ind.key} className="text-center">
              <div className="text-[10px] text-muted-foreground leading-tight">{t(ind.labelKey)}</div>
              <div className={`text-xs font-bold tabular-nums ${v >= 50 ? 'text-green-400' : 'text-muted-foreground'}`}>
                {v.toFixed(0)}%
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
