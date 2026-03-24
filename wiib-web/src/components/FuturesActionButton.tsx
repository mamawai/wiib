import {Loader2, Coins, HandCoins} from 'lucide-react';
import styled from 'styled-components';

interface FuturesActionButtonProps {
  side: 'LONG' | 'SHORT' | 'BUY' | 'SELL';
  leverage?: number;
  label?: string;
  loading?: boolean;
  success?: boolean;
  disabled?: boolean;
  className?: string;
  onClick?: () => void;
}

export function FuturesActionButton({
  side,
  leverage,
  label,
  loading = false,
  success = false,
  disabled = false,
  className,
  onClick,
}: FuturesActionButtonProps) {
  const isSpot = side === 'BUY' || side === 'SELL';
  const actionText = isSpot
    ? `${side === 'BUY' ? '买入' : '卖出'}${label ? ` ${label}` : ''}`
    : `${side === 'LONG' ? '做多' : '做空'} ${leverage}x`;
  const accent = isSpot ? '#f59e0b' : side === 'LONG' ? '#22c55e' : '#ef4444';
  const accentSoft = isSpot ? '#fef3c7' : side === 'LONG' ? '#bbf7d0' : '#fecaca';
  const accentLine = isSpot ? '#fbbf24' : side === 'LONG' ? '#4ade80' : '#f87171';
  const accentDark = isSpot ? '#b45309' : side === 'LONG' ? '#15803d' : '#b91c1c';
  const accentShadow = isSpot
    ? 'rgba(245, 158, 11, 0.34)'
    : side === 'LONG'
      ? 'rgba(34, 197, 94, 0.34)'
      : 'rgba(239, 68, 68, 0.38)';
  const screenIcon = isSpot
    ? (side === 'BUY' ? <Coins size={14} /> : <HandCoins size={14} />)
    : (side === 'LONG' ? '↑' : '↓');

  return (
    <Wrapper
      className={className}
      type="button"
      onClick={onClick}
      disabled={disabled || success}
      $accent={accent}
      $accentSoft={accentSoft}
      $accentLine={accentLine}
      $accentDark={accentDark}
      $accentShadow={accentShadow}
      $success={success}
    >
      <span className="content">
        <span className="left-side" aria-hidden="true">
          <span className="card">
            <span className="card-line" />
            <span className="buttons" />
          </span>
          <span className="post">
            <span className="post-line" />
            <span className="screen">
              <span className="dollar">{screenIcon}</span>
            </span>
            <span className="numbers" />
            <span className="numbers-line2" />
          </span>
        </span>
        <span className="right-side">
          {loading ? <Loader2 className="spinner" /> : <span className="label">{actionText}</span>}
        </span>
      </span>
      <span className="success-layer" aria-hidden={!success}>
        <svg className="success-icon" viewBox="0 0 52 52" fill="none">
          <circle className="success-circle" cx="26" cy="26" r="20" />
          <path className="success-check" d="M16 26.5L23 33.5L36.5 19.5" />
        </svg>
      </span>
    </Wrapper>
  );
}

interface WrapperProps {
  $accent: string;
  $accentSoft: string;
  $accentLine: string;
  $accentDark: string;
  $accentShadow: string;
  $success: boolean;
}

const Wrapper = styled.button<WrapperProps>`
  --accent: ${({ $accent }) => $accent};
  --accent-soft: ${({ $accentSoft }) => $accentSoft};
  --accent-line: ${({ $accentLine }) => $accentLine};
  --accent-dark: ${({ $accentDark }) => $accentDark};
  --accent-shadow: ${({ $accentShadow }) => $accentShadow};

  position: relative;
  display: flex;
  width: 70%;
  height: 56px;
  margin: 0 auto;
  padding: 0;
  border: 0;
  border-radius: 6px;
  overflow: hidden;
  background: ${({ $success }) => ($success ? '#f8fafc' : '#ffffff')};
  color: #111827;
  transition: transform 0.3s ease, box-shadow 0.3s ease, opacity 0.2s ease, background-color 0.3s ease;
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.08);
  cursor: pointer;

  @media (hover: hover) and (pointer: fine) {
    &:hover:not(:disabled) {
      transform: scale(1.02);
      box-shadow: 0 16px 34px rgba(15, 23, 42, 0.12);
    }

    &:hover:not(:disabled) .left-side {
      width: 100%;
    }

    &:hover:not(:disabled) .card {
      animation: slide-top 1.2s cubic-bezier(0.645, 0.045, 0.355, 1) both;
    }

    &:hover:not(:disabled) .post {
      animation: slide-post 1s cubic-bezier(0.165, 0.84, 0.44, 1) both;
    }

    &:hover:not(:disabled) .dollar {
      animation: fade-in-fwd 0.3s 1s backwards;
    }
  }

  &:disabled {
    cursor: not-allowed;
  }

  .content {
    display: flex;
    width: 100%;
    height: 100%;
    opacity: ${({ $success }) => ($success ? 0 : 1)};
    transform: ${({ $success }) => ($success ? 'scale(0.96)' : 'scale(1)')};
    transition: opacity 0.24s ease, transform 0.24s ease;
  }

  .success-layer {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    opacity: ${({ $success }) => ($success ? 1 : 0)};
    pointer-events: none;
    transition: opacity 0.2s ease;
  }

  .success-icon {
    width: 40px;
    height: 40px;
  }

  .success-circle,
  .success-check {
    fill: none;
    stroke: var(--accent-dark);
    stroke-linecap: round;
    stroke-linejoin: round;
  }

  .success-circle {
    stroke-width: 2.5;
    stroke-dasharray: 126;
    stroke-dashoffset: ${({ $success }) => ($success ? 0 : 126)};
    transition: stroke-dashoffset 0.38s ease;
  }

  .success-check {
    stroke-width: 3;
    stroke-dasharray: 28;
    stroke-dashoffset: ${({ $success }) => ($success ? 0 : 28)};
    transition: stroke-dashoffset 0.28s ease 0.28s;
  }

  .left-side {
    position: relative;
    display: flex;
    width: 132px;
    height: 56px;
    flex-shrink: 0;
    align-items: center;
    justify-content: center;
    overflow: hidden;
    border-radius: 6px 0 0 6px;
    background: linear-gradient(135deg, var(--accent) 0%, color-mix(in srgb, var(--accent) 76%, white) 100%);
    transition: width 0.3s ease;
  }

  .right-side {
    display: flex;
    min-width: 0;
    flex: 1;
    align-items: center;
    justify-content: center;
    padding: 0 18px;
    background: #ffffff;
    transition: background-color 0.3s ease;
  }

  @media (hover: hover) and (pointer: fine) {
    &:hover:not(:disabled) .right-side {
      background: #f8fafc;
    }
  }

  .label {
    font-size: 18px;
    font-weight: 800;
    letter-spacing: 0.02em;
    white-space: nowrap;
  }

  .spinner {
    width: 20px;
    height: 20px;
    animation: spin 1s linear infinite;
  }

  .card {
    position: absolute;
    z-index: 10;
    display: flex;
    width: 66px;
    height: 42px;
    flex-direction: column;
    align-items: center;
    border-radius: 8px;
    background: var(--accent-soft);
    box-shadow: 8px 8px 18px -6px var(--accent-shadow);
  }

  .card-line {
    width: 58px;
    height: 10px;
    margin-top: 7px;
    border-radius: 999px;
    background: var(--accent-line);
  }

  .buttons {
    width: 7px;
    height: 7px;
    margin: 9px 0 0 -24px;
    border-radius: 50%;
    transform: rotate(90deg);
    background: var(--accent-dark);
    box-shadow:
      0 -9px 0 0 color-mix(in srgb, var(--accent-dark) 82%, black),
      0 9px 0 0 color-mix(in srgb, var(--accent-dark) 78%, white);
  }

  .post {
    position: absolute;
    top: 56px;
    z-index: 11;
    width: 58px;
    height: 68px;
    overflow: hidden;
    border-radius: 8px;
    background: #d4d4d8;
  }

  .post-line {
    position: absolute;
    top: 7px;
    right: 7px;
    width: 44px;
    height: 8px;
    border-radius: 0 0 3px 3px;
    background: #52525b;
  }

  .post-line::before {
    content: '';
    position: absolute;
    top: -7px;
    width: 44px;
    height: 8px;
    background: #71717a;
  }

  .screen {
    position: absolute;
    top: 20px;
    right: 7px;
    width: 44px;
    height: 21px;
    border-radius: 4px;
    background: #ffffff;
  }

  .dollar {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--accent-dark);
    font-size: 15px;
    font-weight: 900;
  }

  .numbers,
  .numbers-line2 {
    position: absolute;
    left: 22px;
    width: 11px;
    height: 11px;
    border-radius: 2px;
    transform: rotate(90deg);
  }

  .numbers {
    top: 47px;
    background: #71717a;
    box-shadow: 0 -16px 0 0 #71717a, 0 16px 0 0 #71717a;
  }

  .numbers-line2 {
    top: 61px;
    background: #a1a1aa;
    box-shadow: 0 -16px 0 0 #a1a1aa, 0 16px 0 0 #a1a1aa;
  }

  @keyframes slide-top {
    0% {
      transform: translateY(0);
    }

    50%,
    60% {
      transform: translateY(-33px) rotate(90deg);
    }

    100% {
      transform: translateY(-4px) rotate(90deg);
    }
  }

  @keyframes slide-post {
    50% {
      transform: translateY(0);
    }

    100% {
      transform: translateY(-42px);
    }
  }

  @keyframes fade-in-fwd {
    0% {
      opacity: 0;
      transform: translateY(-5px);
    }

    100% {
      opacity: 1;
      transform: translateY(0);
    }
  }

  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }

  @media (max-width: 480px) {
    width: 70%;

    .left-side {
      width: 120px;
    }

    .label {
      font-size: 16px;
    }
  }
`;
