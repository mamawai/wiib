import * as echarts from 'echarts';
import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { getCoin } from '../lib/coinConfig';
import { useIsDark } from '../hooks/useIsDark';

interface CryptoRow {
  symbol: string;
  marketValue: number;
}

interface FuturesRow {
  symbol: string;
  marketValue: number;
}

interface BStockRow {
  ticker: string;
  marketValue: number;
}

// bStock 无 coinConfig 配色，用独立蓝青系列循环取色，与币种暖色区分
const BSTOCK_COLORS = ['#635bff', '#0ea5e9', '#14b8a6', '#6366f1', '#06b6d4', '#3b82f6'];

interface Props {
  cryptoPositions?: CryptoRow[];
  bstockRows?: BStockRow[];
  futuresRows?: FuturesRow[];
  balance: number;
  gameBalance?: number;
}

export function PortfolioChart({ cryptoPositions = [], bstockRows = [], futuresRows = [], balance, gameBalance = 0 }: Props) {
  const chartRef = useRef<HTMLDivElement>(null);
  const isDark = useIsDark();
  const { t, i18n } = useTranslation('portfolio');

  useEffect(() => {
    if (!chartRef.current) return;
    const chart = echarts.init(chartRef.current, isDark ? 'dark' : 'light');

    // 扇区名同时是 tooltip 里认游戏钱包的判据，先取出来，别在 formatter 里再查一次
    const gameWalletName = t('overview.gameWallet');

    const data = [
      ...cryptoPositions
        .filter(c => c.marketValue > 0)
        .map(c => {
          const coin = getCoin(c.symbol);
          return {
            name: coin.name,
            value: c.marketValue,
            itemStyle: { color: coin.chartColor },
          };
        }),
      ...bstockRows
        .filter(b => b.marketValue > 0)
        .map((b, i) => ({
          name: b.ticker,
          value: b.marketValue,
          itemStyle: { color: BSTOCK_COLORS[i % BSTOCK_COLORS.length] },
        })),
      ...futuresRows
        .filter(f => f.marketValue > 0)
        .map(f => {
          const coin = getCoin(f.symbol);
          return {
            name: `${coin.name.toLowerCase()} future`,
            value: f.marketValue,
            itemStyle: { color: coin.chartColor },
          };
        }),
      { name: t('overview.balanceWallet'), value: balance, itemStyle: { color: '#22c55e' } },
      ...(gameBalance > 0 ? [{ name: gameWalletName, value: gameBalance, itemStyle: { color: '#d946ef' } }] : [])
    ];

    const textColor = isDark ? '#878b96' : '#71737b'; // muted-foreground token
    const borderColor = isDark ? '#13151a' : '#FFFFFF'; // Card background

    chart.setOption({
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'item',
        backgroundColor: isDark ? '#13151a' : '#FFFFFF',
        borderColor: isDark ? '#23262e' : '#e4e4df',
        textStyle: { color: isDark ? '#eceef0' : '#17181a' },
        formatter: (params: { marker: string; name: string; value: number; percent: number }) => {
           // 游戏钱包计入总资产但不能直接下单交易，tooltip 里说清楚免得误解
           const note = params.name === gameWalletName
             ? `<br/><span style="font-size:0.8em;opacity:0.7">${t('chart.gameWalletNote')}</span>` : '';
           return `${params.marker}${params.name}<br/>
                   <span style="font-weight:bold; font-size:1.1em">${params.value.toFixed(2)}</span> (${params.percent}%)${note}`;
        }
      },
      legend: {
        bottom: '0%',
        left: 'center',
        textStyle: { color: textColor, fontSize: 11, fontFamily: "'Plus Jakarta Sans Variable', sans-serif" },
        itemWidth: 10,
        itemHeight: 10,
        itemGap: 12,
        icon: 'circle'
      },
      series: [
        {
          name: t('chart.assetAllocation'),
          type: 'pie',
          radius: ['45%', '70%'],
          center: ['50%', '42%'],
          avoidLabelOverlap: false,
          itemStyle: {
            borderRadius: 6,
            borderColor: borderColor,
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
              color: isDark ? '#eceef0' : '#17181a',
              fontFamily: "'Plus Jakarta Sans Variable', sans-serif"
            },
            itemStyle: {
              shadowBlur: 10,
              shadowOffsetX: 0,
              shadowColor: 'rgba(0, 0, 0, 0.5)'
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
    // 依赖里必须带 i18n.language：少了它切语言后 option 不重算，图上还是旧文案
  }, [cryptoPositions, bstockRows, futuresRows, balance, gameBalance, isDark, t, i18n.language]);

  return <div ref={chartRef} className="w-full h-56 sm:h-64 transition-colors duration-300" />;
}
