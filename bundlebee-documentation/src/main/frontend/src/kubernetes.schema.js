import { createRoot } from 'react-dom/client';
import { JsonSchemaViewer } from '@stoplight/json-schema-viewer';
import { Provider, injectStyles } from '@stoplight/mosaic';

function prefixCSS(cssText, containerId) {
  return cssText.replace(/(^|})([^{]+)/g, (_, closeBrace, selectorGroup) => {
    const prefixed = selectorGroup
      .split(',')
      .map(sel => {
        sel = sel.trim();
        if (!sel) {
          return '';
        }
        if (sel.startsWith(`#${containerId}`)) {
          return sel;
        }
        return `#${containerId} ${sel}`;
      })
      .join(',');
    return closeBrace + prefixed;
  });
}

function withScopedDocument(fn) {
  const originalAppendChild = document.head.appendChild;
  document.head.appendChild = function (node) {
    if (node.tagName === 'STYLE') {
      node.textContent = prefixCSS(node.textContent, 'main');
    }
    originalAppendChild.call(this, node);
  };

  try {
    fn();
  } finally {
    document.head.appendChild = originalAppendChild;
  }
}

withScopedDocument(() => injectStyles({ mode: 'system' }));

createRoot(document.getElementById('main'))
  .render(
    <Provider>
      <JsonSchemaViewer {...window.jsonSchemaViewerOpts} />
    </Provider>
  );
