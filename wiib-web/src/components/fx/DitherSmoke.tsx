import { useEffect, useRef } from 'react';

/** 一个"大像素"占多少 CSS px，越大颗粒越粗 */
const PIXEL = 2.5;
/** 抖色量化级数，越少越"版画" */
const COLOR_NUM = 4;

/** 明暗两套烟雾配色：暗=深灰蓝烟，亮=中灰烟（太浅看不见），峰值处混一点品牌橙 */
const PALETTE = {
  dark: { bg: [0.043, 0.047, 0.059], ink: [0.16, 0.18, 0.23], accent: [0.976, 0.451, 0.086] },
  light: { bg: [0.965, 0.965, 0.957], ink: [0.67, 0.67, 0.64], accent: [0.976, 0.451, 0.086] },
};

const VERT = 'attribute vec2 p; void main(){ gl_Position = vec4(p, 0.0, 1.0); }';

/* Perlin fbm 域扭曲出烟形 → Bayer 抖色量化。连续烟雾变成复古颗粒 */
const FRAG = `
precision mediump float;
uniform vec2  u_res;
uniform float u_time;
uniform vec2  u_mouse;      // 低分辨率像素坐标，(-1,-1) 表示不在页面内
uniform vec3  u_bg;
uniform vec3  u_ink;
uniform vec3  u_accent;

vec4 mod289(vec4 x){ return x - floor(x*(1.0/289.0))*289.0; }
vec4 permute(vec4 x){ return mod289(((x*34.0)+10.0)*x); }
vec4 taylorInvSqrt(vec4 r){ return 1.79284291400159 - 0.85373472095314*r; }
vec2 fade(vec2 t){ return t*t*t*(t*(t*6.0-15.0)+10.0); }

float cnoise(vec2 P){
  vec4 Pi = floor(P.xyxy) + vec4(0.0,0.0,1.0,1.0);
  vec4 Pf = fract(P.xyxy) - vec4(0.0,0.0,1.0,1.0);
  Pi = mod289(Pi);
  vec4 ix = Pi.xzxz; vec4 iy = Pi.yyww;
  vec4 fx = Pf.xzxz; vec4 fy = Pf.yyww;
  vec4 i = permute(permute(ix) + iy);
  vec4 gx = fract(i*(1.0/41.0))*2.0 - 1.0;
  vec4 gy = abs(gx) - 0.5;
  vec4 tx = floor(gx + 0.5);
  gx = gx - tx;
  vec2 g00 = vec2(gx.x, gy.x); vec2 g10 = vec2(gx.y, gy.y);
  vec2 g01 = vec2(gx.z, gy.z); vec2 g11 = vec2(gx.w, gy.w);
  vec4 norm = taylorInvSqrt(vec4(dot(g00,g00), dot(g01,g01), dot(g10,g10), dot(g11,g11)));
  g00 *= norm.x; g01 *= norm.y; g10 *= norm.z; g11 *= norm.w;
  float n00 = dot(g00, vec2(fx.x, fy.x));
  float n10 = dot(g10, vec2(fx.y, fy.y));
  float n01 = dot(g01, vec2(fx.z, fy.z));
  float n11 = dot(g11, vec2(fx.w, fy.w));
  vec2 f = fade(Pf.xy);
  vec2 nx = mix(vec2(n00, n01), vec2(n10, n11), f.x);
  return 2.3 * mix(nx.x, nx.y, f.y);
}

float fbm(vec2 p){
  float v = 0.0, a = 1.0;
  for (int i = 0; i < 4; i++) { v += a*abs(cnoise(p)); p *= 3.0; a *= 0.3; }
  return v;
}

/* 2x2 → 4x4 有序抖动矩阵，紧凑写法 */
float bayer2(vec2 a){ a = floor(a); return fract(a.x/2.0 + a.y*a.y*0.75); }
float bayer4(vec2 a){ return bayer2(0.5*a)*0.25 + bayer2(a); }

void main(){
  vec2 uv = gl_FragCoord.xy / u_res - 0.5;
  uv.x *= u_res.x / u_res.y;

  /* 域扭曲：p + fbm(p 随时间平移) → 烟被风缓慢拖动的形态 */
  float f = fbm(uv + fbm(uv - u_time*0.05));

  /* 鼠标吹开：靠近处烟被压下去 */
  if (u_mouse.x >= 0.0) {
    vec2 m = u_mouse / u_res - 0.5;
    m.x *= u_res.x / u_res.y;
    f -= 0.55 * (1.0 - smoothstep(0.0, 0.55, length(uv - m)));
  }

  float d = (bayer4(gl_FragCoord.xy) - 0.5) / ${COLOR_NUM.toFixed(1)};
  float q = clamp(floor((f + d) * ${COLOR_NUM.toFixed(1)}) / ${(COLOR_NUM - 1).toFixed(1)}, 0.0, 1.0);

  /* 峰值处混入品牌橙：mix 而非叠加，亮色底下才不会越加越白 */
  vec3 col = mix(mix(u_bg, u_ink, q), u_accent, pow(q, 3.0) * 0.22);
  gl_FragColor = vec4(col, 1.0);
}`;

/**
 * 抖色烟雾背景：裸 WebGL 一张低分辨率画布，CSS pixelated 放大出大颗粒。
 * 鼠标靠近会把烟"吹开"一个洞；reduced-motion 只画一帧静态烟；
 * WebGL 不可用时保持透明，由登录页的点阵纹理兜底。
 */
export function DitherSmoke({ className }: { className?: string }) {
  const ref = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const cv = ref.current;
    if (!cv) return;
    const gl = cv.getContext('webgl', { antialias: false, depth: false });
    if (!gl) return;

    const prog = gl.createProgram();
    for (const [type, src] of [[gl.VERTEX_SHADER, VERT], [gl.FRAGMENT_SHADER, FRAG]] as const) {
      const s = gl.createShader(type as number)!;
      gl.shaderSource(s, src as string);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
        console.error('DitherSmoke shader:', gl.getShaderInfoLog(s));
        return;
      }
      gl.attachShader(prog, s);
    }
    gl.linkProgram(prog);
    gl.useProgram(prog);
    gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
    const loc = gl.getAttribLocation(prog, 'p');
    gl.enableVertexAttribArray(loc);
    gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
    const u = (name: string) => gl.getUniformLocation(prog, name);
    const uRes = u('u_res'), uTime = u('u_time'), uMouse = u('u_mouse');
    const uBg = u('u_bg'), uInk = u('u_ink'), uAccent = u('u_accent');

    const size = () => {
      cv.width = Math.max(1, Math.ceil(cv.clientWidth / PIXEL));
      cv.height = Math.max(1, Math.ceil(cv.clientHeight / PIXEL));
      gl.viewport(0, 0, cv.width, cv.height);
    };
    size();

    const mouse = { x: -1, y: -1, tx: -1, ty: -1 };
    const render = (t: number) => {
      const p = PALETTE[document.documentElement.classList.contains('dark') ? 'dark' : 'light'];
      gl.uniform2f(uRes, cv.width, cv.height);
      gl.uniform1f(uTime, t);
      gl.uniform2f(uMouse, mouse.x, mouse.y);
      gl.uniform3fv(uBg, p.bg);
      gl.uniform3fv(uInk, p.ink);
      gl.uniform3fv(uAccent, p.accent);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
    };

    const onMove = (e: MouseEvent) => {
      const r = cv.getBoundingClientRect();
      mouse.tx = (e.clientX - r.left) / PIXEL;
      mouse.ty = (r.height - (e.clientY - r.top)) / PIXEL;
    };
    const onLeave = () => { mouse.tx = -1; mouse.ty = -1; };

    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    let raf = 0;
    const t0 = performance.now();
    if (reduced) {
      render(0);
    } else {
      const frame = () => {
        raf = requestAnimationFrame(frame);
        /* 鼠标缓动跟随，吹开的洞有"拖尾"手感 */
        if (mouse.tx >= 0) {
          if (mouse.x < 0) { mouse.x = mouse.tx; mouse.y = mouse.ty; }
          mouse.x += (mouse.tx - mouse.x) * 0.08;
          mouse.y += (mouse.ty - mouse.y) * 0.08;
        } else { mouse.x = -1; mouse.y = -1; }
        render((performance.now() - t0) / 1000);
      };
      raf = requestAnimationFrame(frame);
      window.addEventListener('mousemove', onMove);
      window.addEventListener('mouseleave', onLeave);
    }
    const ro = new ResizeObserver(() => { size(); if (reduced) render(0); });
    ro.observe(cv);

    return () => {
      cancelAnimationFrame(raf);
      ro.disconnect();
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseleave', onLeave);
    };
  }, []);

  return <canvas ref={ref} className={className} style={{ imageRendering: 'pixelated' }} aria-hidden />;
}
