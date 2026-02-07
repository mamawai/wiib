import { useEffect } from 'react';
import * as React from "react";

export function useDedupedEffect(
  key: string | null | undefined,
  effect: () => void | (() => void),
  deps: React.DependencyList,
) {
  useEffect(() => {
    if (key == null) return;
    return effect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
}