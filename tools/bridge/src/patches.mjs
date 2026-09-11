// Must be imported FIRST in bridge.mjs, before the Pera/Defly SDKs are imported, so these
// patches are in place before any SDK code runs (both SDKs call window.open() to launch the
// wallet app / QR modal, and navigator.clipboard on "copy the wc: URI" fallbacks).

window.open = (url) => {
  if (window.FryNative) {
    window.FryNative.openUri(String(url));
  } else {
    console.warn("FryNative missing", url);
  }
  return {
    closed: false,
    close() {},
    focus() {}
  };
};

if (navigator.clipboard) {
  navigator.clipboard.writeText = () => Promise.resolve();
} else {
  navigator.clipboard = { writeText: () => Promise.resolve() };
}

const originalError = console.error.bind(console);
const originalWarn = console.warn.bind(console);

console.error = (...args) => {
  originalError(...args);
  if (window.FryNative && window.FryNative.log) {
    window.FryNative.log("error", args.map(String).join(" "));
  }
};

console.warn = (...args) => {
  originalWarn(...args);
  if (window.FryNative && window.FryNative.log) {
    window.FryNative.log("warn", args.map(String).join(" "));
  }
};
