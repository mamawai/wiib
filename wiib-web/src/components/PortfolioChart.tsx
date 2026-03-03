import * as echarts from 'echarts';
import { useEffect, useRef } from 'react';
import type { Position } from '../types';

interface CryptoRow {
  symbol: string;
  marketValue: number;
}

const CRYPTO_NAMES: Record<string, string> = {
  BTCUSDT: 'BTC',
  PAXGUSDT: 'PAXG',
};

interface Props {
  positions: Position[];
  cryptoPositions?: CryptoRow[];
  balance: number;
  pendingSettlement?: number;
}

export function PortfolioChart({ positions, cryptoPositions = [], balance, pendingSettlement = 0 }: Props) {
  const chartRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!chartRef.current) return;
    const chart = echarts.init(chartRef.current, 'dark');

    const sortedPositions = [...positions].sort((a, b) => (b.marketValue || 0) - (a.marketValue || 0));
    const topPositions = sortedPositions.slice(0, 5);
    const otherValue = sortedPositions.slice(5).reduce((acc, p) => acc + (p.marketValue || 0), 0);

    const data = [
      ...topPositions.map(p => ({
        name: p.stockName,
        value: p.marketValue || 0,
        itemStyle: { color: undefined }
      })),
      ...(otherValue > 0 ? [{ name: '其他股票', value: otherValue, itemStyle: { color: '#666' } }] : []),
      ...cryptoPositions
        .filter(c => c.marketValue > 0)
        .map(c => ({
          name: CRYPTO_NAMES[c.symbol] ?? c.symbol,
          value: c.marketValue,
          itemStyle: { color: c.symbol === 'BTCUSDT' ? '#f97316' : '#eab308' },
        })),
      { name: '现金余额', value: balance, itemStyle: { color: '#22c55e' } },
      ...(pendingSettlement > 0 ? [{ name: '待结算', value: pendingSettlement, itemStyle: { color: '#a855f7' } }] : [])
    ];

    chart.setOption({
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'item',
        formatter: (params: any) => {
           return `${params.marker}${params.name}<br/>
                   <span style="font-weight:bold">${params.value.toFixed(2)}</span> (${params.percent}%)`;
        }
      },
      legend: {
        bottom: '0%',
        left: 'center',
        textStyle: { color: '#888', fontSize: 10 },
        itemWidth: 8,
        itemHeight: 8,
        itemGap: 6
      },
      series: [
        {
          name: '资产分布',
          type: 'pie',
          radius: ['40%', '70%'],
          center: ['50%', '45%'],
          avoidLabelOverlap: false,
          itemStyle: {
            borderRadius: 5,
            borderColor: '#1a1a1a',
            borderWidth: 2
          },
          label: {
            show: false,
            position: 'center'
          },
          emphasis: {
            label: {
              show: true,
              fontSize: 14,
              fontWeight: 'bold',
              color: '#fff'
            }
          },
          labelLine: {
            show: false
          },
          data: data
        }
      ]
    });

    const onResize = () => chart.resize();
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      chart.dispose();
    };
  }, [positions, cryptoPositions, balance, pendingSettlement]);

  return <div ref={chartRef} className="w-full h-48 sm:h-56" />;
}
