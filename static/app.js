const nowTime = document.getElementById('nowTime');
const cityCards = document.getElementById('cityCards');
const citySelect = document.getElementById('citySelect');
const cityName = document.getElementById('cityName');
const tempHigh = document.getElementById('tempHigh');
const tempLow = document.getElementById('tempLow');
const historyBox = document.getElementById('history');
const refreshBtn = document.getElementById('refreshBtn');
const panels = [...document.querySelectorAll('.snap-panel')];
const dots = [...document.querySelectorAll('.dot')];

let activePanelIndex = 0;
let wheelLocked = false;

function tick() { nowTime.textContent = new Date().toLocaleString('zh-CN', { hour12: false }); }
setInterval(tick, 1000); tick();

async function loadLatest() {
  const res = await fetch('/api/weather/latest');
  const data = await res.json();
  cityCards.innerHTML = '';
  citySelect.innerHTML = '';
  data.forEach((d, i) => {
    const opt = document.createElement('option');
    opt.value = d.city; opt.textContent = d.city; citySelect.appendChild(opt);
    const card = document.createElement('div');
    card.className = 'city-card';
    card.style.setProperty('--card-delay', `${Math.min(i * 18, 360)}ms`);
    card.dataset.city = d.city;
    card.dataset.high = d.high_temp;
    card.dataset.low = d.low_temp;
    card.innerHTML = `<div>${d.city}</div><strong>${d.high_temp}° / ${d.low_temp}°</strong>`;
    card.addEventListener('click', () => selectCity(d.city, d.high_temp, d.low_temp));
    cityCards.appendChild(card);
    if (i === 0) updateMain(d.city, d.high_temp, d.low_temp);
  });
  syncSelectedCard(citySelect.value);
}

async function loadCityHistory(city) {
  const res = await fetch(`/api/weather/${encodeURIComponent(city)}`);
  const data = await res.json();
  historyBox.innerHTML = data.map((d, i) => `
    <div class="hist-item" style="--item-delay:${i * 34}ms">
      <span>${d.date}</span>
      <span>${d.high_temp}° / ${d.low_temp}°</span>
    </div>
  `).join('');
}

function updateMain(city, high, low) {
  cityName.textContent = city;
  animateNumber(tempHigh, Number(high));
  tempLow.textContent = low;
  syncSelectedCard(city);
}

function animateNumber(el, nextValue) {
  if (!Number.isFinite(nextValue)) {
    el.textContent = '--';
    return;
  }

  const startValue = Number.parseInt(el.textContent, 10);
  const from = Number.isFinite(startValue) ? startValue : nextValue;
  const duration = 420;
  const start = performance.now();

  function frame(now) {
    const progress = Math.min((now - start) / duration, 1);
    const eased = 1 - Math.pow(1 - progress, 3);
    el.textContent = Math.round(from + (nextValue - from) * eased);
    if (progress < 1) requestAnimationFrame(frame);
  }

  requestAnimationFrame(frame);
}

async function selectCity(city, high, low) {
  citySelect.value = city;
  updateMain(city, high, low);
  await loadCityHistory(city);
  snapToPanel(1);
}

function syncSelectedCard(city) {
  [...cityCards.children].forEach(card => {
    card.classList.toggle('active', card.dataset.city === city);
  });
}

citySelect.addEventListener('change', async (e) => {
  const city = e.target.value;
  const card = [...cityCards.children].find(c => c.dataset.city === city);
  if (card) {
    updateMain(city, card.dataset.high, card.dataset.low);
  }
  await loadCityHistory(city);
});

refreshBtn.addEventListener('click', async () => {
  refreshBtn.disabled = true;
  refreshBtn.textContent = '爬取中...';
  await fetch('/api/crawl', { method: 'POST' });
  await loadLatest();
  await loadCityHistory(citySelect.value || '广州');
  refreshBtn.disabled = false;
  refreshBtn.textContent = '立即爬取';
});

function setActivePanel(index) {
  activePanelIndex = index;
  panels.forEach((panel, i) => panel.classList.toggle('active', i === index));
  dots.forEach((dot, i) => dot.classList.toggle('active', i === index));
}

function snapToPanel(index) {
  const targetIndex = Math.max(0, Math.min(index, panels.length - 1));
  panels[targetIndex].scrollIntoView({ behavior: 'smooth', block: 'start' });
  setActivePanel(targetIndex);
}

function canScrollInside(el, deltaY) {
  const scrollBox = el.closest('.history, .city-cards');
  if (!scrollBox) return false;

  const hasOverflow = scrollBox.scrollHeight > scrollBox.clientHeight + 2;
  if (!hasOverflow) return false;

  const atTop = scrollBox.scrollTop <= 0;
  const atBottom = scrollBox.scrollTop + scrollBox.clientHeight >= scrollBox.scrollHeight - 2;
  return (deltaY < 0 && !atTop) || (deltaY > 0 && !atBottom);
}

dots.forEach((dot, index) => {
  dot.addEventListener('click', () => snapToPanel(index));
});

const panelObserver = new IntersectionObserver((entries) => {
  const visible = entries
    .filter(entry => entry.isIntersecting)
    .sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];

  if (!visible) return;
  const index = panels.indexOf(visible.target);
  if (index >= 0) setActivePanel(index);
}, { threshold: [0.45, 0.7] });

panels.forEach(panel => panelObserver.observe(panel));

window.addEventListener('wheel', (event) => {
  if (window.matchMedia('(max-width: 900px)').matches) return;
  if (canScrollInside(event.target, event.deltaY)) return;
  if (Math.abs(event.deltaY) < 18 || wheelLocked) return;

  event.preventDefault();
  wheelLocked = true;
  snapToPanel(activePanelIndex + Math.sign(event.deltaY));
  window.setTimeout(() => { wheelLocked = false; }, 760);
}, { passive: false });

(async function init() {
  setActivePanel(0);
  await loadLatest();
  if (citySelect.value) await loadCityHistory(citySelect.value);
})();
