/*
 * Copyright (c) 2021 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
const assert = require('node:assert/strict');
const path = require('node:path');
const {test} = require('node:test');
const {buildSync} = require('esbuild');
const {renderToStaticMarkup} = require('react-dom/server');

test('schema viewer renders the supplied schema without editable fields', () => {
    const schema = {
        type: 'object',
        properties: {replicas: {type: 'integer', description: 'Number of pods'}},
    };
    const elements = [];
    const main = {};
    const document = {
        getElementById: (id) => id === 'main' ? main : null,
        createElement: (tag) => ({tag}),
        head: {appendChild: (element) => elements.push(element)},
    };
    let rendered;
    const compiled = buildSync({
        entryPoints: [path.join(__dirname, '../src/kubernetes.schema.js')],
        bundle: true,
        write: false,
        format: 'cjs',
        loader: {'.js': 'jsx', '.css': 'empty'},
        jsx: 'automatic',
        external: ['react', 'react-dom/client', 'jsonjoy-builder'],
    }).outputFiles[0].text;
    const load = (name) => name.endsWith('.css') ? {} : name === 'react-dom/client' ? {
        createRoot: (target) => {
            assert.equal(target, main);
            return {render: (element) => { rendered = element; }};
        },
    } : require(name);
    new Function('require', 'window', 'document', compiled)(load, {
        jsonSchemaViewerOpts: {base: '/bundlebee/', schema},
    }, document);

    assert.equal(rendered.props.readOnly, true);
    assert.equal(rendered.props.value, schema);
    const html = renderToStaticMarkup(rendered);
    assert.match(html, /replicas/);
    assert.match(html, /Number of pods/);
    assert.doesNotMatch(html, /<(?:input|textarea|select)\b/);
    assert.equal(elements[0].href, '/bundlebee/generated/js/kubernetes.schema.css');
});
