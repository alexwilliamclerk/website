const nowTime = document.getElementById('nowTime');
const cityCards = document.getElementById('cityCards');
const citySelect = document.getElementById('citySelect');
const cityName = document.getElementById('cityName');
const tempHigh = document.getElementById('tempHigh');
const tempLow = document.getElementById('tempLow');
const historyBox = document.getElementById('history');
const refreshBtn = document.getElementById('refreshBtn');

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
    card.innerHTML = `<div>${d.city}</div><strong>${d.high_temp}° / ${d.low_temp}°</strong>`;
    cityCards.appendChild(card);
    if (i === 0) updateMain(d.city, d.high_temp, d.low_temp);
  });
}

async function loadCityHistory(city) {
  const res = await fetch(`/api/weather/${encodeURIComponent(city)}`);
  const data = await res.json();
  historyBox.innerHTML = data.map(d => `<div class="hist-item"><span>${d.date}</span><span>${d.high_temp}° / ${d.low_temp}°</span></div>`).join('');
}

function updateMain(city, high, low) {
  cityName.textContent = city;
  tempHigh.textContent = high;
  tempLow.textContent = low;
}

citySelect.addEventListener('change', async (e) => {
  const city = e.target.value;
  const card = [...cityCards.children].find(c => c.firstChild.textContent === city);
  if (card) {
    const temps = card.querySelector('strong').textContent.match(/(-?\d+)°\s*\/\s*(-?\d+)°/);
    if (temps) updateMain(city, temps[1], temps[2]);
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

(async function init() {
  await loadLatest();
  if (citySelect.value) await loadCityHistory(citySelect.value);
})();
