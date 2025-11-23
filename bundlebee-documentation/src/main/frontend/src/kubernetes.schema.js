import 'jsonjoy-builder/styles.css';
import { createRoot } from 'react-dom/client';
import { SchemaVisualEditor } from 'jsonjoy-builder';

(function injectCssLink(url) {
  const id = 'kubernetes-schema-css';
  if (document.getElementById(id)) {
    return;
  }

  const link = document.createElement('link');
  link.id = id;
  link.rel = 'stylesheet';
  link.href = url;
  link.onerror = (e) => console.error('Failed to load stylesheet:', e);

  document.head ? document.head.appendChild(link) : document.documentElement.appendChild(link);
})(window.jsonSchemaViewerOpts.base + 'generated/js/kubernetes.schema.css');

createRoot(document.getElementById('main'))
  .render(
      <SchemaVisualEditor schema={window.jsonSchemaViewerOpts.schema} />
  );
