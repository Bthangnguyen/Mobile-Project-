import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const DATE_STR = new Date().toISOString().split('T')[0];

const TARGETS = [
  // Meta
  {
    dir: 'meta',
    file: 'pages-api-overview.md',
    url: 'https://developers.facebook.com/documentation/pages-api/overview'
  },
  {
    dir: 'meta',
    file: 'pages-api-posts.md',
    url: 'https://developers.facebook.com/documentation/pages-api/posts'
  },
  {
    dir: 'meta',
    file: 'pages-api-comments-mentions.md',
    url: 'https://developers.facebook.com/docs/pages-api/comments-mentions/'
  },
  {
    dir: 'meta',
    file: 'graph-comments-edge.md',
    url: 'https://developers.facebook.com/docs/graph-api/reference/object/comments'
  },
  {
    dir: 'meta',
    file: 'page-photos.md',
    url: 'https://developers.facebook.com/docs/graph-api/reference/page/photos/'
  },
  {
    dir: 'meta',
    file: 'page-insights.md',
    url: 'https://developers.facebook.com/docs/graph-api/reference/page/insights/'
  },
  {
    dir: 'meta',
    file: 'graph-changelog.md',
    url: 'https://developers.facebook.com/docs/graph-api/changelog'
  },
  {
    dir: 'meta',
    file: 'changelog-v25.md',
    url: 'https://developers.facebook.com/docs/graph-api/changelog/version25.0'
  },
  {
    dir: 'meta',
    file: 'permissions-features.md',
    url: 'https://developers.facebook.com/docs/pages/overview/permissions-features'
  },
  {
    dir: 'meta',
    file: 'permissions-reference.md',
    url: 'https://developers.facebook.com/docs/permissions'
  },
  {
    dir: 'meta',
    file: 'page-reference.md',
    url: 'https://developers.facebook.com/docs/graph-api/reference/page'
  },
  {
    dir: 'meta',
    file: 'error-handling.md',
    url: 'https://developers.facebook.com/docs/graph-api/guides/error-handling'
  },
  {
    dir: 'meta',
    file: 'rate-limiting.md',
    url: 'https://developers.facebook.com/docs/graph-api/overview/rate-limiting/'
  },
  {
    dir: 'meta',
    file: 'access-tokens.md',
    url: 'https://developers.facebook.com/documentation/facebook-login/guides/access-tokens'
  },
  {
    dir: 'meta',
    file: 'long-lived-page-token.md',
    url: 'https://developers.facebook.com/docs/facebook-login/guides/access-tokens/get-long-lived'
  },
  {
    dir: 'meta',
    file: 'auth-vs-data-access.md',
    url: 'https://developers.facebook.com/documentation/facebook-login/auth-vs-data'
  },
  {
    dir: 'meta',
    file: 'login-for-business.md',
    url: 'https://developers.facebook.com/docs/facebook-login/facebook-login-for-business/'
  },
  {
    dir: 'meta',
    file: 'manual-login-flow.md',
    url: 'https://developers.facebook.com/documentation/facebook-login/guides/advanced/manual-flow'
  },
  {
    dir: 'meta',
    file: 'pages-api-llms.txt',
    url: 'https://developers.facebook.com/documentation/pages-api/llms.txt'
  },

  // LinkedIn
  {
    dir: 'linkedin',
    file: 'native-pkce.md',
    url: 'https://learn.microsoft.com/en-us/linkedin/shared/authentication/authorization-code-flow-native'
  },
  {
    dir: 'linkedin',
    file: 'authorization-code-flow.md',
    url: 'https://learn.microsoft.com/en-us/linkedin/shared/authentication/authorization-code-flow'
  },
  {
    dir: 'linkedin',
    file: 'programmatic-refresh-tokens.md',
    url: 'https://learn.microsoft.com/en-us/linkedin/shared/authentication/programmatic-refresh-tokens'
  },
  {
    dir: 'linkedin',
    file: 'increasing-access.md',
    url: 'https://learn.microsoft.com/en-us/linkedin/marketing/increasing-access'
  },
  {
    dir: 'linkedin',
    file: 'rate-limits.md',
    url: 'https://learn.microsoft.com/en-us/linkedin/shared/api-guide/concepts/rate-limits'
  },
  {
    dir: 'linkedin',
    file: 'posts-api.md',
    url: 'https://learn.microsoft.com/en-us/linkedin/marketing/integrations/community-management/shares/posts-api'
  },
  {
    dir: 'linkedin',
    file: 'little-text-format.md',
    url: 'https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/little-text-format'
  },
  {
    dir: 'linkedin',
    file: 'multiimage-post-api.md',
    url: 'https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/multiimage-post-api'
  },
  {
    dir: 'linkedin',
    file: 'comments-api.md',
    url: 'https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/comments-api'
  },
  {
    dir: 'linkedin',
    file: 'network-update-social-actions.md',
    url: 'https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/network-update-social-actions'
  },
  {
    dir: 'linkedin',
    file: 'social-metadata-api.md',
    url: 'https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/social-metadata-api'
  },
  {
    dir: 'linkedin',
    file: 'organization-access-control-by-role.md',
    url: 'https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/organization-access-control-by-role'
  },
  {
    dir: 'linkedin',
    file: 'organization-authorizations.md',
    url: 'https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/organization-authorizations/getting-started'
  },
  {
    dir: 'linkedin',
    file: 'share-statistics.md',
    url: 'https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/share-statistics'
  },
  {
    dir: 'linkedin',
    file: 'follower-statistics.md',
    url: 'https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/follower-statistics'
  },
  {
    dir: 'linkedin',
    file: 'organization-network-size.md',
    url: 'https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/organization-network-size'
  },
  {
    dir: 'linkedin',
    file: 'sign-in-with-linkedin-v2.md',
    url: 'https://learn.microsoft.com/en-us/linkedin/consumer/integrations/self-serve/sign-in-with-linkedin-v2'
  }
];

function decodeHtml(html) {
  return html
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#039;/g, "'")
    .replace(/&#39;/g, "'")
    .replace(/&apos;/g, "'")
    .replace(/&#064;/g, '@')
    .replace(/&#123;/g, '{')
    .replace(/&#125;/g, '}')
    .replace(/&nbsp;/g, ' ')
    .replace(/&#(\d+);/g, (_, code) => String.fromCharCode(Number(code)))
    .replace(/&#x([a-fA-F0-9]+);/g, (_, hex) => String.fromCharCode(parseInt(hex, 16)));
}

function convertHtmlToMarkdown(html, url) {
  let title = '';
  const titleMatch = html.match(/<title[^>]*>([^<]+)<\/title>/i);
  if (titleMatch) {
    title = decodeHtml(titleMatch[1].trim());
  }

  let mainHtml = html;
  const docBodyIdx = html.indexOf('id="documentation_body_pagelet"');
  if (docBodyIdx !== -1) {
    mainHtml = html.substring(docBodyIdx);
    const bodyEndIdx = mainHtml.indexOf('</body>');
    if (bodyEndIdx !== -1) {
      mainHtml = mainHtml.substring(0, bodyEndIdx);
    }
  } else {
    const mainMatch = html.match(/<main[^>]*>([\s\S]*?)<\/main>/i) ||
                      html.match(/<article[^>]*>([\s\S]*?)<\/article>/i) ||
                      html.match(/<div[^>]+id=["']main["'][^>]*>([\s\S]*?)<\/div>/i) ||
                      html.match(/<div[^>]+class=["'][^"']*content[^"']*["'][^>]*>([\s\S]*?)<\/div>/i);
    if (mainMatch) {
      mainHtml = mainMatch[1];
    }
  }

  // Remove script, style, nav, svg, header, footer
  mainHtml = mainHtml
    .replace(/<script[\s\S]*?<\/script>/gi, '')
    .replace(/<style[\s\S]*?<\/style>/gi, '')
    .replace(/<nav[\s\S]*?<\/nav>/gi, '')
    .replace(/<header[\s\S]*?<\/header>/gi, '')
    .replace(/<footer[\s\S]*?<\/footer>/gi, '')
    .replace(/<svg[\s\S]*?<\/svg>/gi, '')
    .replace(/<!--[\s\S]*?-->/g, '');

  // Pre-process code blocks
  const codeBlocks = [];
  mainHtml = mainHtml.replace(/<pre[^>]*><code(?:\s+class=["'](?:language-)?([a-zA-Z0-9_-]+)["'])?[^>]*>([\s\S]*?)<\/code><\/pre>/gi, (match, lang, code) => {
    const cleanCode = decodeHtml(code.replace(/<[^>]+>/g, ''));
    const token = `__CODE_BLOCK_${codeBlocks.length}__`;
    codeBlocks.push(`\n\`\`\`${lang || ''}\n${cleanCode.trim()}\n\`\`\`\n`);
    return token;
  });

  mainHtml = mainHtml.replace(/<pre[^>]*>([\s\S]*?)<\/pre>/gi, (match, code) => {
    const cleanCode = decodeHtml(code.replace(/<[^>]+>/g, ''));
    const token = `__CODE_BLOCK_${codeBlocks.length}__`;
    codeBlocks.push(`\n\`\`\`\n${cleanCode.trim()}\n\`\`\`\n`);
    return token;
  });

  // Pre-process tables
  mainHtml = mainHtml.replace(/<table[^>]*>([\s\S]*?)<\/table>/gi, (match, tableBody) => {
    const rows = [];
    const trMatches = tableBody.match(/<tr[^>]*>[\s\S]*?<\/tr>/gi) || [];
    
    for (const tr of trMatches) {
      const cells = [];
      const cellMatches = tr.match(/<(th|td)[^>]*>([\s\S]*?)<\/\1>/gi) || [];
      for (const cell of cellMatches) {
        const text = cell.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
        cells.push(decodeHtml(text));
      }
      if (cells.length > 0) {
        rows.push(cells);
      }
    }

    if (rows.length === 0) return '';
    const maxCols = Math.max(...rows.map(r => r.length));
    if (maxCols === 0) return '';

    let mdTable = '\n';
    const headerRow = rows[0];
    while (headerRow.length < maxCols) headerRow.push('');
    mdTable += '| ' + headerRow.join(' | ') + ' |\n';
    mdTable += '| ' + Array(maxCols).fill('---').join(' | ') + ' |\n';

    for (let i = 1; i < rows.length; i++) {
      const row = rows[i];
      while (row.length < maxCols) row.push('');
      mdTable += '| ' + row.join(' | ') + ' |\n';
    }
    mdTable += '\n';
    return mdTable;
  });

  // Inline formatting
  mainHtml = mainHtml
    .replace(/<h1[^>]*>([\s\S]*?)<\/h1>/gi, '\n# $1\n')
    .replace(/<h2[^>]*>([\s\S]*?)<\/h2>/gi, '\n## $1\n')
    .replace(/<h3[^>]*>([\s\S]*?)<\/h3>/gi, '\n### $1\n')
    .replace(/<h4[^>]*>([\s\S]*?)<\/h4>/gi, '\n#### $1\n')
    .replace(/<h5[^>]*>([\s\S]*?)<\/h5>/gi, '\n##### $1\n')
    .replace(/<h6[^>]*>([\s\S]*?)<\/h6>/gi, '\n###### $1\n')
    .replace(/<blockquote[^>]*>([\s\S]*?)<\/blockquote>/gi, (m, b) => {
      const lines = b.replace(/<[^>]+>/g, '').trim().split('\n');
      return '\n' + lines.map(l => '> ' + l.trim()).join('\n') + '\n';
    })
    .replace(/<li[^>]*>([\s\S]*?)<\/li>/gi, '\n- $1')
    .replace(/<p[^>]*>([\s\S]*?)<\/p>/gi, '\n\n$1\n\n')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<code[^>]*>([\s\S]*?)<\/code>/gi, '`$1`')
    .replace(/<(strong|b)[^>]*>([\s\S]*?)<\/\1>/gi, '**$2**')
    .replace(/<(em|i)[^>]*>([\s\S]*?)<\/\1>/gi, '*$2*')
    .replace(/<a[^>]+href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi, '[$2]($1)');

  let text = mainHtml.replace(/<[^>]+>/g, '');
  text = decodeHtml(text);

  for (let i = 0; i < codeBlocks.length; i++) {
    text = text.replace(`__CODE_BLOCK_${i}__`, codeBlocks[i]);
  }

  text = text.replace(/\n{3,}/g, '\n\n').trim();
  const header = `# ${title || path.basename(url)}\n\n> **Source URL:** ${url}  \n> **Fetched Date:** ${DATE_STR}  \n\n---\n\n`;
  return header + text + '\n';
}

function fetchWithCurl(url) {
  return execFileSync('curl.exe', ['-s', '-L', url], {
    encoding: 'utf8',
    maxBuffer: 50 * 1024 * 1024,
    timeout: 30000
  });
}

async function run() {
  console.log(`Starting download of ${TARGETS.length} reference documents...`);
  let successCount = 0;
  let failCount = 0;

  for (let i = 0; i < TARGETS.length; i++) {
    const item = TARGETS[i];
    const outDir = path.resolve('docs', 'references', item.dir);
    if (!fs.existsSync(outDir)) {
      fs.mkdirSync(outDir, { recursive: true });
    }
    const outPath = path.join(outDir, item.file);

    // Skip if already downloaded and has good content (> 2500 bytes)
    if (fs.existsSync(outPath)) {
      const existingSize = fs.statSync(outPath).size;
      if (existingSize > 2500) {
        console.log(`[${i + 1}/${TARGETS.length}] Skipping ${item.dir}/${item.file} (already complete, ${existingSize} bytes)`);
        successCount++;
        continue;
      }
    }

    console.log(`[${i + 1}/${TARGETS.length}] Fetching ${item.url} -> ${item.dir}/${item.file}`);
    try {
      let bodyText = fetchWithCurl(item.url);

      if (!bodyText || bodyText.trim().length === 0) {
        throw new Error('Empty response from curl');
      }

      let mdContent = '';
      if (item.file.endsWith('.txt') || bodyText.startsWith('# ') || !bodyText.includes('<html')) {
        mdContent = `# ${item.file}\n\n> **Source URL:** ${item.url}  \n> **Fetched Date:** ${DATE_STR}  \n\n---\n\n${decodeHtml(bodyText)}\n`;
      } else {
        mdContent = convertHtmlToMarkdown(bodyText, item.url);
      }

      fs.writeFileSync(outPath, mdContent, 'utf8');
      console.log(`  SUCCESS: Saved ${mdContent.length} chars to ${item.dir}/${item.file}`);
      successCount++;
    } catch (err) {
      console.error(`  ERROR fetching ${item.url}:`, err.message);
      failCount++;
    }

    await new Promise(r => setTimeout(r, 200));
  }

  console.log(`\n========================================`);
  console.log(`Download finished: ${successCount} succeeded, ${failCount} failed.`);
  console.log(`========================================\n`);
}

run();
