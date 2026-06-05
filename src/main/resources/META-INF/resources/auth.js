// Auth guard — inclus dans <head> sans defer sur toutes les pages protégées.
// Masque immédiatement le DOM, vérifie le token, redirige si invalide.

document.documentElement.style.visibility = 'hidden';

(async () => {
  try {
    const token = localStorage.getItem('auth_token');
    if (!token) { window.location.replace('/login.html'); return; }

    const r = await fetch('/api/auth/check', {
      headers: { Authorization: 'Bearer ' + token }
    });
    if (!r.ok) {
      localStorage.removeItem('auth_token');
      window.location.replace('/login.html');
      return;
    }

    // Auth OK — révèle la page
    document.documentElement.style.visibility = '';
    document.addEventListener('DOMContentLoaded', injectLogout);
    if (document.readyState !== 'loading') injectLogout();

  } catch (_) {
    // Erreur réseau : affiche quand même la page (serveur temporairement injoignable)
    document.documentElement.style.visibility = '';
  }
})();

function injectLogout() {
  const btn = document.createElement('button');
  btn.textContent = 'Déconnexion';
  Object.assign(btn.style, {
    position: 'fixed', bottom: '16px', right: '16px', zIndex: '9999',
    background: 'rgba(22,27,34,.92)', color: '#8b949e',
    border: '1px solid #30363d', borderRadius: '6px',
    padding: '6px 14px', fontSize: '12px', cursor: 'pointer',
    backdropFilter: 'blur(4px)', transition: 'color .15s'
  });
  btn.onmouseover = () => { btn.style.color = '#c9d1d9'; };
  btn.onmouseout  = () => { btn.style.color = '#8b949e'; };
  btn.onclick = async () => {
    const t = localStorage.getItem('auth_token');
    if (t) {
      await fetch('/api/auth/logout', {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + t }
      }).catch(() => {});
    }
    localStorage.removeItem('auth_token');
    window.location.replace('/login.html');
  };
  document.body.appendChild(btn);
}
