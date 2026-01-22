import { useEffect, useState } from 'react';

interface TypewriterProps {
  text: string;
  speed?: number;
  deleteSpeed?: number;
  delayBeforeDelete?: number;
  loop?: boolean;
}

export function Typewriter({ 
  text, 
  speed = 80, 
  deleteSpeed = 30, 
  delayBeforeDelete = 2000, 
  loop = true 
}: TypewriterProps) {
  const [displayed, setDisplayed] = useState('');
  const [isTyping, setIsTyping] = useState(true);

  useEffect(() => {
    let i = 0;
    let isDeleting = false;
    let timer: ReturnType<typeof setTimeout>;

    const tick = () => {
      if (!isDeleting) {
        if (i < text.length) {
          setDisplayed(text.slice(0, i + 1));
          i++;
          timer = setTimeout(tick, speed);
        } else {
          setIsTyping(false);
          if (loop) {
            timer = setTimeout(() => {
              isDeleting = true;
              setIsTyping(true);
              tick();
            }, delayBeforeDelete);
          }
        }
      } else {
        if (i > 0) {
          i--;
          setDisplayed(text.slice(0, i));
          timer = setTimeout(tick, deleteSpeed);
        } else {
          isDeleting = false;
          setIsTyping(true);
          timer = setTimeout(tick, speed);
        }
      }
    };

    timer = setTimeout(tick, speed);
    return () => clearTimeout(timer);
  }, [text, speed, deleteSpeed, delayBeforeDelete, loop]);

  return (
    <span>
      {displayed}
      <span className={`inline-block w-0.5 h-4 ml-0.5 align-middle bg-primary ${isTyping ? 'animate-pulse' : 'opacity-0'}`} />
    </span>
  );
}
