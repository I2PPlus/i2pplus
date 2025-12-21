/*
    Snowflakes © 2024 Denis Seleznev
    https://github.com/hcodes/snowflakes/
    MIT License
    Modified by dr|z3d for I2P+
    Canvas-based optimization for better performance
*/

let animationId = null;
let ctx = null;

function initSnowflakes() {
  if (document.getElementById('snowflakeCanvas')) {return;}

  const theme = typeof window.theme !== 'undefined' ? window.theme : 'light';
  const canvas = document.createElement('canvas');
  canvas.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;z-index:999999999;pointer-events:none;';
  canvas.id = 'snowflakeCanvas';
  document.body.appendChild(canvas);

   ctx = canvas.getContext('2d');

  const resizeCanvas = () => {
    canvas.width = window.innerWidth;
    canvas.height = window.innerHeight;
  };
  resizeCanvas();
  window.addEventListener('resize', resizeCanvas);

  const urlParams = new URLSearchParams(window.location.search);
  console.log("Detected theme = " + theme);
  const baseColor = ({ light: '#87CEEB', dark: '#337733', classic: '#B0E0E6', midnight: '#9662ca' })[theme] || '#87CEEB';

  const svgCache = {};
  const windTypes = ['gentle', 'moderate', 'strong', 'gusty', 'swirling'];
  const windSpeeds = { gentle: 0.12, moderate: 0.22, strong: 0.35, gusty: 0.5, swirling: 0.65 };

  const loadSvgFlakes = async () => {
    for (let i = 1; i <= 6; i++) {
      try {
        const res = await fetch(`/js/snowflakes/flake${i}.svg`);
        svgCache[i] = await res.text();
      } catch (e) {
        console.warn(`Failed to load flake${i}.svg:`, e);
      }
    }
  };

  class CanvasSnowflake {
    constructor(baseColor, canvas) {
      this.canvas = canvas;
      this.initialSize = Math.random() * 6 + 8;
      this.size = this.initialSize;
      this.x = Math.random() * canvas.width;
      this.y = Math.random() * canvas.height - this.size;
      this.color = this.varyColor(baseColor, 8);
      const baseSpeed = Math.random() * 0.08 + 0.05;
      this.speed = Math.max(0.35, baseSpeed * (this.size / 11));
      this.initialOpacity = Math.random() * 0.3 + 0.7;
      this.opacity = this.initialOpacity;
      this.rotation = Math.random() * Math.PI * 3;
      this.rotationSpeed = (Math.random() - 0.5) * 0.045;
      this.flakeType = (Math.floor(Math.random() * 6) + 1);
      this.windType = windTypes[(Math.random() * windTypes.length) | 0];
      this.windPhase = Math.random() * Math.PI * 2;
      this.windSpeed = windSpeeds[this.windType] || 0.22;
      this.vx = 0;
      this.vy = this.speed;
      this.totalDistance = 0;
      this.maxDistance = canvas.height + this.size;
      this.createColoredSvg();
    }

    async createColoredSvg() {
      const svgText = svgCache[this.flakeType];
      if (!svgText) return;
      const colored = svgText.replace(/:color:/g, this.color);
      const blob = new Blob([colored], { type: 'image/svg+xml' });
      const url = URL.createObjectURL(blob);
      this.svgImage = new Image();
      this.svgImage.onload = () => URL.revokeObjectURL(url);
      this.svgImage.src = url;
    }

    varyColor(hex, maxVar) {
      const r = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex);
      if (!r) return hex;
      const c = {
        r: parseInt(r[1], 16),
        g: parseInt(r[2], 16),
        b: parseInt(r[3], 16)
      };
      const vr = (Math.random() * maxVar * 2 - maxVar) | 0;
      const vg = (Math.random() * maxVar * 2 - maxVar) | 0;
      const vb = (Math.random() * maxVar * 2 - maxVar) | 0;
      return `rgb(${Math.max(0, Math.min(255, c.r + vr))},${Math.max(0, Math.min(255, c.g + vg))},${Math.max(0, Math.min(255, c.b + vb))})`;
    }

    update() {
      this.windPhase += 0.02;
      this.vx = Math.sin(this.windPhase) * this.windSpeed;
      this.x += this.vx;
      this.y += this.vy;
      this.rotation += Math.max(this.rotationSpeed, 0.01);
      this.totalDistance += this.vy;
      const deteriorationProgress = Math.min(1, this.totalDistance / this.maxDistance);
      if (deteriorationProgress < 0.25) {
        const progress = deteriorationProgress * 4;
        this.size = this.initialSize * (1 - 0.05 * progress);
        this.opacity = this.initialOpacity * (1 - 0.1 * progress);
      } else if (deteriorationProgress < 0.5) {
        const progress = (deteriorationProgress - 0.25) * 4;
        this.size = this.initialSize * (0.95 - 0.1 * progress);
        this.opacity = this.initialOpacity * (0.9 - 0.2 * progress);
      } else if (deteriorationProgress < 0.75) {
        const progress = (deteriorationProgress - 0.5) * 4;
        this.size = this.initialSize * (0.85 - 0.15 * progress);
        this.opacity = this.initialOpacity * (0.7 - 0.3 * progress);
      } else {
        const progress = (deteriorationProgress - 0.75) * 4;
        this.size = this.initialSize * (0.7 - 0.2 * progress);
        this.opacity = this.initialOpacity * (0.4 - 0.3 * progress);
      }

      if (this.x > this.canvas.width + this.size) this.x = -this.size;
      else if (this.x < -this.size) this.x = this.canvas.width + this.size;

      if (this.y > this.canvas.height + this.size) {
        this.y = -this.size;
        this.x = Math.random() * this.canvas.width;
        this.totalDistance = 0;
        this.size = this.initialSize;
        this.opacity = this.initialOpacity;
      }
    }

    draw(ctx) {
      if (!this.svgImage || !this.svgImage.complete) return;
      ctx.save();
      ctx.globalAlpha = this.opacity;
      ctx.translate(this.x, this.y);
      ctx.rotate(this.rotation);
      const s = this.size;
      ctx.drawImage(this.svgImage, -s, -s, s * 2, s * 2);
      ctx.restore();
    }
  }

  const flakeCount = (Math.random() * 6 + 8) | 0;

  loadSvgFlakes().then(() => {
    const snowflakes = Array.from({ length: flakeCount }, () => new CanvasSnowflake(baseColor, canvas));
    const animate = () => {
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      let i = snowflakes.length;
      while (i--) {
        snowflakes[i].update();
        snowflakes[i].draw(ctx);
      }
      animationId = requestAnimationFrame(animate);
    };
    animate();
  });
}

function pauseSnow() {
  if (animationId) {
    cancelAnimationFrame(animationId);
    animationId = null;
  }

  const canvas = document.getElementById('snowflakeCanvas');
  if (canvas) {
    canvas.remove();
  }

  ctx = null;
}

document.addEventListener('DOMContentLoaded', () => {
  initSnowflakes();
  if (document.visibilityState === "visible") initSnowflakes();
  document.addEventListener("visibilitychange", () => {
    document.visibilityState === "visible" ? initSnowflakes() : pauseSnow();
  });
});