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
  const baseColor = ({ light: '#87ceeb', dark: '#5a9d68', classic: '#b0e0e6', midnight: '#9662ca' })[theme] || '#87ceeb';
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
      this.initialSize = Math.max(10, Math.random() * 9 + 8);
      this.size = this.initialSize;
      this.x = Math.random() * canvas.width;
      this.y = Math.random() * canvas.height - this.size;
      this.color = this.varyColor(baseColor, 10);
      this.speed = Math.max(0.35, 0.03 + (this.size / 11) * 0.08);
      this.initialOpacity = Math.random() * 0.3 + 0.7;
      this.opacity = this.initialOpacity;
      this.rotation = Math.random() * Math.PI * 3;
      this.rotationSpeed = (Math.random() - 0.5) * 0.045;
      this.isDying = false;
      this.deathSpinSpeed = 0;
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

      const festiveTints = [
        { r: 255, g: 69, b: 0 },
        { r: 255, g: 140, b: 0 },
        { r: 255, g: 215, b: 0 },
        { r: 255, g: 192, b: 203 },
        { r: 186, g: 85, b: 211 },
        { r: 220, g: 20, b: 60 }
      ];

      if (Math.random() < 0.5) {
        const tint = festiveTints[Math.floor(Math.random() * festiveTints.length)];
        const mixRatio = 0.05;
        const nr = Math.round(c.r * (1 - mixRatio) + tint.r * mixRatio);
        const ng = Math.round(c.g * (1 - mixRatio) + tint.g * mixRatio);
        const nb = Math.round(c.b * (1 - mixRatio) + tint.b * mixRatio);
        const vr = (Math.random() * maxVar - maxVar/2) | 0;
        const vg = (Math.random() * maxVar - maxVar/2) | 0;
        const vb = (Math.random() * maxVar - maxVar/2) | 0;
        return `rgb(${Math.max(0, Math.min(255, nr + vr))},${Math.max(0, Math.min(255, ng + vg))},${Math.max(0, Math.min(255, nb + vb))})`;
      }

      const vr = (Math.random() * maxVar * 3 - maxVar) | 0;
      const vg = (Math.random() * maxVar * 3 - maxVar) | 0;
      const vb = (Math.random() * maxVar * 3 - maxVar) | 0;
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

      const shouldRemove = this.opacity <= 0.2 || this.size < 8 || this.opacity < 0.5 && this.size <= 10;
      
      if (shouldRemove) {
        if (!this.isDying) {
          this.isDying = true;
          this.deathSpinSpeed = 0.5;
          this.deathFallSpeed = this.vy;
        }
        
        this.rotation += this.deathSpinSpeed;
        this.deathSpinSpeed += 0.02;
        this.deathFallSpeed += 0.3;
        this.vy = this.deathFallSpeed;
        this.y += this.vy;
        
        if (this.opacity <= 0.05 || this.size <= 2 || this.y > this.canvas.height + this.size) {
          this.y = -this.initialSize;
          this.x = Math.random() * this.canvas.width;
          this.totalDistance = 0;
          this.size = this.initialSize;
          this.opacity = this.initialOpacity;
          this.vy = this.speed;
          this.isDying = false;
          this.deathSpinSpeed = 0;
          this.deathFallSpeed = 0;
          return;
        }
      } else {
        if (this.opacity < 0.5) {
          this.targetSize = Math.max(0, this.size - this.initialSize * 0.25);
          this.size += (this.targetSize - this.size) * 0.3;
        }
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
      if (!this.svgImage || !this.svgImage.complete || this.size <= 0) return;
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