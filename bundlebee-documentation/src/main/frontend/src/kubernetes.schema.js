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

  const root = document.head || document.documentElement;
  root.appendChild(link);

  const inlineStyleOverrides = document.createElement('style');
  // override optional button style but not required one
  inlineStyleOverrides.innerHTML = 'button.bg-secondary.text-muted-foreground{background-color:white !important;}';
  root.appendChild(inlineStyleOverrides);
})(window.jsonSchemaViewerOpts.base + 'generated/js/kubernetes.schema.css');

createRoot(document.getElementById('main'))
  .render(
      <SchemaVisualEditor readOnly={true} schema={window.jsonSchemaViewerOpts.schema} />
  );
