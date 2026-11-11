(() => {
  const term = new Terminal({
    cursorBlink: true,
    fontFamily: 'monospace',
    fontSize: 14,
    scrollback: 5000,
    theme: { background: '#000000', foreground: '#f5f9fb', cursor: '#29c5ef' }
  });
  const fit = new FitAddon.FitAddon();
  term.loadAddon(fit);
  term.open(document.getElementById('terminal'));
  const keyboardInput = document.querySelector('.xterm-helper-textarea');
  if (keyboardInput) {
    keyboardInput.setAttribute('autocomplete', 'off');
    keyboardInput.setAttribute('autocorrect', 'off');
    keyboardInput.setAttribute('autocapitalize', 'off');
    keyboardInput.setAttribute('spellcheck', 'false');
  }
  term.onData(data => AndroidTerminal.send(data));
  term.onResize(({ rows, cols }) => AndroidTerminal.resize(rows, cols));

  window.terminalReceive = base64 => {
    const raw = atob(base64);
    const bytes = new Uint8Array(raw.length);
    for (let index = 0; index < raw.length; index++) bytes[index] = raw.charCodeAt(index);
    term.write(bytes);
  };
  window.terminalText = value => term.write(value);
  window.terminalFocus = () => term.focus();

  const resize = () => {
    if (document.getElementById('terminal').clientWidth > 0) fit.fit();
  };
  if (window.ResizeObserver) new ResizeObserver(resize).observe(document.getElementById('terminal'));
  window.addEventListener('resize', resize);
  requestAnimationFrame(resize);
})();
