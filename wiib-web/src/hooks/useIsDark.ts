import { useState, useEffect } from 'react';

export function useIsDark() {
  const [isDark, setIsDark] = useState(() => document.documentElement.classList.contains('dark'));
  useEffect(() => {
    const ob = new MutationObserver(() => setIsDark(document.documentElement.classList.contains('dark')));
    ob.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
    return () => ob.disconnect();
  }, []);
  return isDark;
}
