import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Pickaxe, Spade, Diamond, Gamepad2 } from 'lucide-react';
import { cn } from '../lib/utils';

// 静态数据数组存的是 key 不是文案：key 写成字面量才 grep 得到，
// 拼 `${g.key}.title` 那种动态 key 一改词表就没人知道谁在用
const GAMES = [
  {
    key: 'mines',
    path: '/mines',
    icon: Pickaxe,
    titleKey: 'mines.title',
    descKey: 'mines.desc',
    color: 'text-amber-400',
    bg: 'bg-amber-500/10 hover:bg-amber-500/20',
  },
  {
    key: 'blackjack',
    path: '/blackjack',
    icon: Spade,
    titleKey: 'blackjack.title',
    descKey: 'blackjack.desc',
    color: 'text-emerald-400',
    bg: 'bg-emerald-500/10 hover:bg-emerald-500/20',
  },
  {
    key: 'videopoker',
    path: '/videopoker',
    icon: Diamond,
    titleKey: 'videoPoker.title',
    descKey: 'videoPoker.desc',
    color: 'text-purple-400',
    bg: 'bg-purple-500/10 hover:bg-purple-500/20',
  },
] as const;

export function Games() {
  const navigate = useNavigate();
  const { t } = useTranslation('games');

  return (
    <div className="max-w-2xl mx-auto px-4 py-8 space-y-6">
      <div className="flex items-center justify-center gap-2.5">
        <div className="p-1.5 rounded-lg bg-pink-500/10">
          <Gamepad2 className="w-5 h-5 text-pink-500" />
        </div>
        <h1 className="text-xl font-bold">{t('title')}</h1>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        {GAMES.map(g => (
          <button
            key={g.key}
            onClick={() => navigate(g.path)}
            className={cn(
              'flex flex-col items-center gap-3 rounded-lg p-6 pt-card machined hover:bg-surface-hover transition-colors cursor-pointer',
              g.bg,
            )}
          >
            <g.icon className={cn('w-10 h-10', g.color)} />
            <div className="text-center">
              <div className="font-bold text-lg">{t(g.titleKey)}</div>
              <div className="text-xs text-muted-foreground mt-1">{t(g.descKey)}</div>
            </div>
          </button>
        ))}
      </div>
    </div>
  );
}
