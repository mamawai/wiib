import * as echarts from 'echarts';
import { useEffect, useRef } from 'react';
import type { Position } from '../types';

interface Props {
  positions: Position[];
  balance: number;
  pendingSettlement?: number;
}

export function PortfolioChart({ positions, balance, pendingSettlement = 0 }: Props) {
  const chartRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!chartRef.current) return;
    const chart = echarts.init(chartRef.current, 'dark');

    // Prepare data: Top 5 stocks + Others + Cash + Pending
    const sortedPositions = [...positions].sort((a, b) => (b.marketValue || 0) - (a.marketValue || 0));
    const topPositions = sortedPositions.slice(0, 5);
    const otherValue = sortedPositions.slice(5).reduce((acc, p) => acc + (p.marketValue || 0), 0);

    const data = [
      ...topPositions.map(p => ({
        name: p.stockName,
        value: p.marketValue || 0,
        itemStyle: { color: undefined } // Let echarts pick colors
      })),
      ...(otherValue > 0 ? [{ name: '其他股票', value: otherValue, itemStyle: { color: '#666' } }] : []),
      { name: '现金余额', value: balance, itemStyle: { color: '#22c55e' } },
      ...(pendingSettlement > 0 ? [{ name: '待结算', value: pendingSettlement, itemStyle: { color: '#eab308' } }] : [])
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
        itemWidth: 10,
        itemHeight: 10
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
  }, [positions, balance, pendingSettlement]);

  return <div ref={chartRef} className="w-full h-64" />;
}
