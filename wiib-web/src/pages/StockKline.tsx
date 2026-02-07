import { useState, useEffect, useRef, useMemo } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import * as echarts from 'echarts';
import { stockApi } from '../api';
import { TickChart } from '../components/TickChart';
import { Card, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Skeleton } from '../components/ui/skeleton';
import { ArrowLeft, Loader2 } from 'lucide-react';
import type { Kline, DayTick } from '../types';

export function StockKline() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const { name, code } = (location.state as { name?: string; code?: string }) || {};

  const chartRef = useRef<HTMLDivElement>(null);
  const [klineData, setKlineData] = useState<Kline[]>([]);
  const [hoveredDate, setHoveredDate] = useState<string | null>(null);
  const [tickData, setTickData] = useState<DayTick[]>([]);
  const [loading, setLoading] = useState(true);
  const [tickLoading, setTickLoading] = useState(false);

  useEffect(() => {
    if (!id) return;
    stockApi.kline(Number(id), 30)
      .then(setKlineData)
      .finally(() => setLoading(false));
  }, [id]);

  const sortedKline = useMemo(() =>
    [...klineData].sort((a, b) => a.date.localeCompare(b.date)),
    [klineData],
  );

  const hoveredPrevClose = useMemo(() => {
    if (!hoveredDate) return undefined;
    return sortedKline.find(k => k.date === hoveredDate)?.open;
  }, [hoveredDate, sortedKline]);

  // K线图
  useEffect(() => {
    if (!chartRef.current || sortedKline.length === 0) return;

    const chart = echarts.init(chartRef.current, 'dark');
    const dates = sortedKline.map(k => k.date);
    // candlestick: [open, close, low, high]
    const ohlcData = sortedKline.map(k => [k.open, k.close, k.low, k.high]);

    chart.setOption({
      backgroundColor: 'transparent',
      grid: { left: 60, right: 20, top: 20, bottom: 40 },
      xAxis: {
        type: 'category',
        data: dates,
        axisLabel: { color: '#888', fontSize: 11 },
        axisLine: { lineStyle: { color: '#333' } },
        axisTick: { show: false },
      },
      yAxis: {
        type: 'value',
        scale: true,
        axisLabel: { color: '#888', fontSize: 11, formatter: (v: number) => v.toFixed(2) },
        axisLine: { show: false },
        splitLine: { lineStyle: { color: '#222', type: 'dashed' } },
      },
      series: [{
        type: 'candlestick',
        data: ohlcData,
        itemStyle: {
          color: '#ef4444',
          color0: '#22c55e',
          borderColor: '#ef4444',
          borderColor0: '#22c55e',
        },
      }],
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'cross', crossStyle: { color: '#555' } },
        backgroundColor: 'rgba(20, 20, 20, 0.9)',
        borderColor: '#333',
        textStyle: { color: '#fff', fontSize: 12 },
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        formatter: (params: any) => {
          const d = params?.[0];
          if (!d?.data) return '';
          // ECharts candlestick d.data = [categoryIndex, open, close, low, high]
          const [, open, close, low, high] = d.data;
          if (open == null) return '';
          const color = close >= open ? '#ef4444' : '#22c55e';
          return `<div style="padding:4px 0">
            <div style="color:#888;margin-bottom:4px">${d.name}</div>
            <div>开: ${Number(open).toFixed(2)}</div>
            <div>收: <span style="color:${color}">${Number(close).toFixed(2)}</span></div>
            <div>高: ${Number(high).toFixed(2)}</div>
            <div>低: ${Number(low).toFixed(2)}</div>
          </div>`;
        },
      },
      dataZoom: [{ type: 'inside', start: 0, end: 100 }],
    });

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    chart.on('updateAxisPointer', (event: any) => {
      const idx = event.axesInfo?.[0]?.value;
      if (typeof idx === 'number' && idx >= 0 && idx < dates.length) {
        setHoveredDate(dates[idx]);
      }
    });

    const onResize = () => chart.resize();
    window.addEventListener('resize', onResize);

    return () => {
      window.removeEventListener('resize', onResize);
      chart.dispose();
    };
  }, [sortedKline]);

  // 悬停加载分时（带内存缓存 + 请求取消）
  const tickCache = useRef<Map<string, DayTick[]>>(new Map());
  useEffect(() => {
    if (!hoveredDate || !id) return;

    const cached = tickCache.current.get(hoveredDate);
    if (cached) {
      setTickData(cached);
      setTickLoading(false);
      return;
    }

    const controller = new AbortController();
    setTickLoading(true);
    stockApi.historyTicks(Number(id), hoveredDate, controller.signal)
      .then(data => {
        tickCache.current.set(hoveredDate, data);
        setTickData(data);
      })
      .catch(() => {})
      .finally(() => { if (!controller.signal.aborted) setTickLoading(false); });
    return () => controller.abort();
  }, [hoveredDate, id]);

  if (loading) {
    return (
      <div className="max-w-4xl mx-auto p-4 space-y-4">
        <Skeleton className="h-8 w-20" />
        <Card><CardContent className="p-4"><Skeleton className="h-[400px] w-full" /></CardContent></Card>
      </div>
    );
  }

  return (
    <div className="max-w-4xl mx-auto p-4 space-y-4">
      <div className="flex items-center gap-3">
        <Button variant="ghost" size="sm" onClick={() => navigate(-1)} className="gap-1">
          <ArrowLeft className="w-4 h-4" />
          返回
        </Button>
        {name && <span className="text-lg font-bold">{name}</span>}
        {code && <span className="text-sm text-muted-foreground">{code}</span>}
      </div>

      <Card>
        <CardContent className="p-4">
          {sortedKline.length > 0 ? (
            <div ref={chartRef} className="w-full" style={{ height: 400 }} />
          ) : (
            <div className="py-16 text-center text-muted-foreground text-sm">暂无K线数据</div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardContent className="p-4">
          {hoveredDate ? (
            <>
              <div className="text-sm text-muted-foreground mb-2">{hoveredDate} 分时走势</div>
              {tickLoading ? (
                <div className="flex justify-center py-16">
                  <Loader2 className="w-6 h-6 animate-spin text-muted-foreground" />
                </div>
              ) : tickData.length > 0 ? (
                <TickChart key={hoveredDate} ticks={tickData} prevClose={hoveredPrevClose} />
              ) : (
                <div className="py-16 text-center text-muted-foreground text-sm">暂无数据</div>
              )}
            </>
          ) : (
            <div className="py-16 text-center text-muted-foreground text-sm">
              在K线图上移动鼠标查看当日分时
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
