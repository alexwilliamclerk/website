const nowTime = document.getElementById('nowTime');
const cityCards = document.getElementById('cityCards');
const cityPicker = document.getElementById('cityPicker');
const cityPickerTrigger = document.getElementById('cityPickerTrigger');
const cityPickerValue = document.getElementById('cityPickerValue');
const cityPickerList = document.getElementById('cityPickerList');
const citySearch = document.getElementById('citySearch');
const citySelect = document.getElementById('citySelect');
const cityName = document.getElementById('cityName');
const tempHigh = document.getElementById('tempHigh');
const tempLow = document.getElementById('tempLow');
const historyBox = document.getElementById('history');
const refreshBtn = document.getElementById('refreshBtn');
const metricAqi = document.getElementById('metricAqi');
const metricHumidity = document.getElementById('metricHumidity');
const metricWindSpeed = document.getElementById('metricWindSpeed');
const metricUvIndex = document.getElementById('metricUvIndex');
const metricVisibility = document.getElementById('metricVisibility');
const metricPressure = document.getElementById('metricPressure');
const historyPanel = document.getElementById('historyPanel');

let latestByCity = new Map();
let pointerFrame = 0;
let cityPickerOpen = false;

const motionObserver = 'IntersectionObserver' in window
  ? new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return;
      entry.target.classList.add('in-view');
      motionObserver.unobserve(entry.target);
    });
  }, { rootMargin: '0px 0px -12% 0px', threshold: 0.16 })
  : null;

function observeMotionElements(root = document) {
  const targets = root instanceof Element
    ? [root, ...root.querySelectorAll('.reveal, .city-card, .hist-item')]
    : [...root.querySelectorAll('.reveal, .city-card, .hist-item')];

  targets
    .filter(el => el.matches('.reveal, .city-card, .hist-item'))
    .forEach((el) => {
      if (motionObserver) {
        motionObserver.observe(el);
      } else {
        el.classList.add('in-view');
      }
    });
}

function updateScrollEffects() {
  const scrollable = document.documentElement.scrollHeight - window.innerHeight;
  const progress = scrollable > 0 ? window.scrollY / scrollable : 0;
  document.documentElement.style.setProperty('--scroll-progress', progress.toFixed(4));
  document.documentElement.style.setProperty('--scroll-shift-y', `${progress * -26}px`);
}

function updatePointerEffects(event) {
  if (pointerFrame) return;

  pointerFrame = requestAnimationFrame(() => {
    const x = (event.clientX / window.innerWidth - 0.5) * 2;
    const y = (event.clientY / window.innerHeight - 0.5) * 2;
    document.documentElement.style.setProperty('--pointer-x', x.toFixed(3));
    document.documentElement.style.setProperty('--pointer-y', y.toFixed(3));
    document.documentElement.style.setProperty('--noise-x', `${x * -10}px`);
    document.documentElement.style.setProperty('--noise-y', `${y * -8}px`);
    pointerFrame = 0;
  });
}

function easeHistoryIntoView() {
  if (!historyPanel) return;
  const rect = historyPanel.getBoundingClientRect();
  const comfortableTop = window.innerHeight * 0.18;
  const comfortableBottom = window.innerHeight * 0.82;
  if (rect.top < comfortableTop || rect.top > comfortableBottom) {
    historyPanel.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }
}

function tick() { nowTime.textContent = new Date().toLocaleString('zh-CN', { hour12: false }); }
setInterval(tick, 1000); tick();

function setCityPickerOpen(open) {
  cityPickerOpen = open;
  cityPicker.classList.toggle('open', open);
  cityPickerTrigger.setAttribute('aria-expanded', String(open));
  if (open) {
    citySearch.value = '';
    renderCityPickerList();
    window.requestAnimationFrame(() => citySearch.focus());
  }
}

function renderCityPickerList() {
  const query = citySearch.value.trim().toLowerCase();
  const selectedCity = citySelect.value || cityName.textContent;
  const records = [...latestByCity.values()].filter((record) => {
    if (!query) return true;
    return record.city.toLowerCase().includes(query);
  });

  cityPickerList.innerHTML = '';
  if (!records.length) {
    cityPickerList.innerHTML = '<div class="city-empty">没有匹配城市</div>';
    return;
  }

  records.forEach((record) => {
    const option = document.createElement('button');
    option.type = 'button';
    option.className = 'city-option';
    option.dataset.city = record.city;
    option.setAttribute('role', 'option');
    option.setAttribute('aria-selected', String(record.city === selectedCity));
    option.innerHTML = `
      <span>
        <strong>${record.city}</strong>
        <small>${record.high_temp}° / ${record.low_temp}°</small>
      </span>
      <span class="city-option-check" aria-hidden="true"></span>
    `;
    option.addEventListener('click', async () => {
      await selectCity(record);
      setCityPickerOpen(false);
      cityPickerTrigger.focus();
    });
    cityPickerList.appendChild(option);
  });
}

function syncCityPicker(city) {
  if (!city) return;
  cityPickerValue.textContent = city;
  [...cityPickerList.children].forEach((option) => {
    if (!option.classList?.contains('city-option')) return;
    option.setAttribute('aria-selected', String(option.dataset.city === city));
  });
}

async function loadLatest() {
  const res = await fetch('/api/weather/latest');
  const data = await res.json();
  cityCards.innerHTML = '';
  citySelect.innerHTML = '';
  latestByCity = new Map(data.map(d => [d.city, d]));
  data.forEach((d, i) => {
    const opt = document.createElement('option');
    opt.value = d.city; opt.textContent = d.city; citySelect.appendChild(opt);
    const card = document.createElement('div');
    card.className = 'city-card';
    card.style.setProperty('--card-delay', `${Math.min(i * 18, 360)}ms`);
    card.dataset.city = d.city;
    card.innerHTML = `<div>${d.city}</div><strong>${d.high_temp}° / ${d.low_temp}°</strong>`;
    card.addEventListener('click', () => selectCity(d, true));
    cityCards.appendChild(card);
    if (i === 0) updateMain(d);
  });
  renderCityPickerList();
  syncSelectedCard(citySelect.value);
  observeMotionElements(cityCards);
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
  observeMotionElements(historyBox);
}

function metricValue(value) {
  return value === null || value === undefined || value === '' ? '--' : value;
}

function renderMetrics(record = {}) {
  metricAqi.textContent = metricValue(record.aqi);
  metricHumidity.textContent = metricValue(record.humidity);
  metricWindSpeed.textContent = metricValue(record.wind_speed);
  metricUvIndex.textContent = metricValue(record.uv_index);
  metricVisibility.textContent = metricValue(record.visibility);
  metricPressure.textContent = metricValue(record.pressure);
}

function updateMain(record) {
  cityName.textContent = record.city;
  animateNumber(tempHigh, Number(record.high_temp));
  tempLow.textContent = record.low_temp;
  renderMetrics(record);
  syncSelectedCard(record.city);
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

async function selectCity(record, shouldScroll = false) {
  citySelect.value = record.city;
  updateMain(record);
  await loadCityHistory(record.city);
  if (shouldScroll) easeHistoryIntoView();
}

function syncSelectedCard(city) {
  syncCityPicker(city);
  [...cityCards.children].forEach(card => {
    card.classList.toggle('active', card.dataset.city === city);
  });
}

citySelect.addEventListener('change', async (e) => {
  const city = e.target.value;
  const record = latestByCity.get(city);
  if (record) {
    updateMain(record);
  }
  await loadCityHistory(city);
});

cityPickerTrigger.addEventListener('click', () => {
  setCityPickerOpen(!cityPickerOpen);
});

cityPickerTrigger.addEventListener('keydown', (event) => {
  if (event.key === 'ArrowDown' || event.key === 'Enter' || event.key === ' ') {
    event.preventDefault();
    setCityPickerOpen(true);
  }
});

citySearch.addEventListener('input', renderCityPickerList);

cityPicker.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') {
    setCityPickerOpen(false);
    cityPickerTrigger.focus();
  }
});

document.addEventListener('pointerdown', (event) => {
  if (!cityPickerOpen || cityPicker.contains(event.target)) return;
  setCityPickerOpen(false);
});

refreshBtn.addEventListener('click', async () => {
  refreshBtn.disabled = true;
  refreshBtn.textContent = '爬取中...';
  try {
    await fetch('/api/crawl', { method: 'POST' });
    await loadLatest();
    await loadCityHistory(citySelect.value || '广州');
  } finally {
    refreshBtn.disabled = false;
    refreshBtn.textContent = '立即爬取';
  }
});

window.addEventListener('scroll', updateScrollEffects, { passive: true });
window.addEventListener('pointermove', updatePointerEffects, { passive: true });

(async function init() {
  observeMotionElements();
  updateScrollEffects();
  await loadLatest();
  if (citySelect.value) await loadCityHistory(citySelect.value);
})();
