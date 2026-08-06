(() => {
  const port = browser.runtime.connectNative("browser_bridge");
  port.onMessage.addListener(async message => {
    const id = message && message.id;
    if (!id || !message.script) return;
    try {
      const value = await (0, eval)(message.script);
      port.postMessage({ id, ok: true, value: value === undefined ? null : value });
    } catch (error) {
      port.postMessage({ id, ok: false, error: String(error && error.message || error) });
    }
  });
  port.postMessage({ type: "ready", href: location.href });
})();
