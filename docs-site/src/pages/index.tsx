import type {ReactNode} from 'react';
import clsx from 'clsx';
import Link from '@docusaurus/Link';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import Layout from '@theme/Layout';
import CodeBlock from '@theme/CodeBlock';

import styles from './index.module.css';

/**
 * The project's front page.
 *
 * Kept short. Every claim on it is one the documentation backs up and links to,
 * and the five capability cards mirror the site's Capabilities section, in the
 * order data moves through Aran. The source table and the status page carry the
 * limits, so the page does not have to hedge in the hero.
 */

type CapabilityLevel = 'full' | 'none';

interface RepoRow {
  type: string;
  href: string;
  connect: CapabilityLevel;
  connectNote: string;
  browse: CapabilityLevel;
  browseNote?: string;
  importNote?: string;
  federatedNote?: string;
}

function IconDatabase({size = 24, style}: {size?: number; style?: React.CSSProperties}): ReactNode {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      style={style}>
      <ellipse cx="12" cy="5" rx="7" ry="2.5" />
      <path d="M5 5v6c0 1.4 3.1 2.5 7 2.5s7-1.1 7-2.5V5" />
      <path d="M5 11v6c0 1.4 3.1 2.5 7 2.5s7-1.1 7-2.5v-6" />
    </svg>
  );
}

function IconGraphNodes({size = 24}: {size?: number}): ReactNode {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true">
      <circle cx="6" cy="6" r="2.6" />
      <circle cx="18" cy="6" r="2.6" />
      <circle cx="12" cy="18" r="2.6" />
      <path d="M8.4 7 L15.6 7 M7.6 8.2 L10.7 15.8 M16.4 8.2 L13.3 15.8" />
    </svg>
  );
}

function IconQuery({size = 24}: {size?: number}): ReactNode {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true">
      <line x1="3" y1="6" x2="14" y2="6" />
      <line x1="3" y1="11" x2="12" y2="11" />
      <line x1="3" y1="16" x2="10" y2="16" />
      <circle cx="15.5" cy="14.5" r="4" />
      <line x1="18.3" y1="17.3" x2="21" y2="20" />
    </svg>
  );
}

function IconProfile({size = 24}: {size?: number}): ReactNode {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <line x1="5" y1="20" x2="5" y2="12" />
      <line x1="10" y1="20" x2="10" y2="6" />
      <line x1="15" y1="20" x2="15" y2="10" />
      <line x1="20" y1="20" x2="20" y2="4" />
    </svg>
  );
}

function IconServe({size = 24}: {size?: number}): ReactNode {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="3" y="4" width="18" height="6" rx="1.5" />
      <rect x="3" y="14" width="18" height="6" rx="1.5" />
      <line x1="7" y1="7" x2="7.01" y2="7" />
      <line x1="7" y1="17" x2="7.01" y2="17" />
    </svg>
  );
}

const mutedIconStyle: React.CSSProperties = {flexShrink: 0, color: 'var(--aran-muted-foreground)'};

function IconFile(): ReactNode {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" style={mutedIconStyle}>
      <path d="M6 2h9l4 4v16H6z" />
      <path d="M15 2v4h4" />
      <line x1="9" y1="13" x2="15" y2="13" />
      <line x1="9" y1="17" x2="15" y2="17" />
    </svg>
  );
}

function IconDocStore(): ReactNode {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" style={mutedIconStyle}>
      <rect x="5" y="9" width="12" height="9" rx="1.5" />
      <rect x="8" y="5" width="12" height="9" rx="1.5" style={{fill: 'var(--aran-background)'}} />
    </svg>
  );
}

function IconGlobe(): ReactNode {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" style={mutedIconStyle}>
      <circle cx="12" cy="12" r="8" />
      <ellipse cx="12" cy="12" rx="3.4" ry="8" />
      <line x1="4" y1="12" x2="20" y2="12" />
    </svg>
  );
}

function IconGraphCluster(): ReactNode {
  return (
    <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="var(--aran-primary)" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="5" cy="6" r="2" />
      <circle cx="19" cy="6" r="2" />
      <circle cx="12" cy="12" r="2.2" />
      <circle cx="6" cy="18" r="2" />
      <circle cx="18" cy="18" r="2" />
      <path d="M6.7 7.2 L10.4 10.6 M17.3 7.2 L13.6 10.6 M10.4 13.4 L7.3 16.2 M13.6 13.4 L16.7 16.2" />
    </svg>
  );
}

function Dot({level}: {level: CapabilityLevel}): ReactNode {
  if (level === 'full') {
    return (
      <svg width="10" height="10" viewBox="0 0 12 12" aria-hidden="true" style={{flexShrink: 0}}>
        <circle cx="6" cy="6" r="5" fill="var(--aran-success)" />
      </svg>
    );
  }
  return (
    <svg width="10" height="10" viewBox="0 0 12 12" aria-hidden="true" style={{flexShrink: 0}}>
      <circle cx="6" cy="6" r="5" fill="none" stroke="var(--aran-border)" strokeWidth="1.5" />
    </svg>
  );
}

const CAPABILITIES = [
  {
    title: 'Connect',
    body: 'Files, PostgreSQL, MySQL, MariaDB, S3-compatible storage, Iceberg REST catalogs and Singer taps. Copy a table or stream into the lake, or read databases and Iceberg tables live.',
    to: '/docs/capabilities/connect',
    icon: <IconDatabase />,
  },
  {
    title: 'Derive and profile',
    body: 'Aran reads each source and generates a schema, an editable SQL wrapper and column statistics. Nobody writes a schema by hand.',
    to: '/docs/capabilities/derive-and-profile',
    icon: <IconProfile />,
  },
  {
    title: 'Align and version',
    body: 'Join proposals come with evidence. You map sources onto one vocabulary stored as a graph, then diff, promote and roll back versions.',
    to: '/docs/capabilities/align-and-version',
    icon: <IconGraphNodes />,
  },
  {
    title: 'Query',
    body: 'Write OQL against the integrated vocabulary. Calcite plans it with cost estimates from the profiles, and DuckDB runs it.',
    to: '/docs/capabilities/query',
    icon: <IconQuery />,
  },
  {
    title: 'Serve',
    body: 'Save a query as a data product, stored as a snapshot or recomputed on every read. SQL clients read it over JDBC, Iceberg engines through a read-only catalog and AI agents over MCP. Scripts and notebooks run OQL over REST or the Python SDK.',
    to: '/docs/capabilities/serve',
    icon: <IconServe />,
  },
];

const REPOSITORIES: RepoRow[] = [
  {
    type: 'Relational databases',
    href: '/docs/repositories/relational-databases',
    connect: 'full',
    connectNote: 'JDBC connect',
    browse: 'full',
    browseNote: 'table list',
    importNote: 'Per table',
    federatedNote: 'PostgreSQL, MySQL, MariaDB',
  },
  {
    type: 'Iceberg REST catalogs',
    href: '/docs/repositories/iceberg-rest-catalogs',
    connect: 'full',
    connectNote: 'protocol hello',
    browse: 'full',
    browseNote: 'table list',
    importNote: 'Per table',
    federatedNote: 'Yes',
  },
  {
    type: 'Singer taps',
    href: '/docs/repositories/singer-taps',
    connect: 'full',
    connectNote: 'tap run',
    browse: 'full',
    browseNote: 'stream list',
    importNote: 'Per stream',
  },
  {
    type: 'Object storage',
    href: '/docs/repositories/object-storage',
    connect: 'full',
    connectNote: 'HTTP probe',
    browse: 'none',
  },
  {
    type: 'File uploads',
    href: '/docs/repositories/file-uploads',
    connect: 'none',
    connectNote: 'upload-only',
    browse: 'none',
  },
];

const SOURCE_NODES: Array<{label: string; top: number; icon: ReactNode}> = [
  {label: 'PostgreSQL', top: 38, icon: <IconDatabase size={18} style={mutedIconStyle} />},
  {label: 'CSV file', top: 122, icon: <IconFile />},
  {label: 'S3 bucket', top: 206, icon: <IconDocStore />},
  {label: 'Singer tap', top: 290, icon: <IconGlobe />},
];

const CONSUMER_NODES = [
  {label: 'Query', sub: 'OQL and SQL', top: 54},
  {label: 'Tools', sub: 'JDBC, REST, Iceberg, Python', top: 164},
  {label: 'AI agents', sub: 'MCP, read-only', top: 274},
];

const OQL_SAMPLE = `SELECT IPaper.title, IResearcher.name
FROM IPaper -[hasAuthor]-> IResearcher
WHERE IResearcher.affiliation = 'UPC'`;

const QUICKSTART = `git clone https://github.com/dtak-upc/aran
cd aran
docker compose -f compose.standalone.yml up --build`;

function IntegrationDiagram(): ReactNode {
  return (
    <>
      <div className={styles.diagramWrap}>
        <svg className={styles.diagramSvg} viewBox="0 0 560 400" aria-hidden="true">
          <path d="M150,74 C182,74 182,200 210,200" fill="none" style={{stroke: 'var(--aran-border)'}} strokeWidth="1.5" />
          <path d="M150,158 C182,158 196,200 210,200" fill="none" style={{stroke: 'var(--aran-border)'}} strokeWidth="1.5" />
          <path d="M150,242 C182,242 196,200 210,200" fill="none" style={{stroke: 'var(--aran-border)'}} strokeWidth="1.5" />
          <path d="M150,326 C182,326 182,200 210,200" fill="none" style={{stroke: 'var(--aran-border)'}} strokeWidth="1.5" />
          <path d="M350,200 C378,200 378,90 410,90" fill="none" style={{stroke: 'var(--aran-primary)'}} strokeWidth="1.5" opacity="0.45" />
          <path d="M350,200 C378,200 378,200 410,200" fill="none" style={{stroke: 'var(--aran-primary)'}} strokeWidth="1.5" opacity="0.45" />
          <path d="M350,200 C378,200 378,310 410,310" fill="none" style={{stroke: 'var(--aran-primary)'}} strokeWidth="1.5" opacity="0.45" />
        </svg>

        {SOURCE_NODES.map((s) => (
          <div key={s.label} className={styles.diagramNode} style={{left: 0, top: s.top}}>
            {s.icon}
            <span className={styles.diagramNodeLabel}>{s.label}</span>
          </div>
        ))}

        <div className={styles.diagramGraph}>
          <IconGraphCluster />
          <span className={styles.diagramGraphLabel}>One graph</span>
        </div>

        {CONSUMER_NODES.map((c) => (
          <div
            key={c.label}
            className={clsx(styles.diagramNode, styles.diagramNodeStack)}
            style={{left: 410, top: c.top}}>
            <span className={styles.diagramNodeLabel}>{c.label}</span>
            <span className={styles.diagramNodeSub}>{c.sub}</span>
          </div>
        ))}
      </div>
      <p className={styles.diagramCaption}>
        Four kinds of source in, one queryable vocabulary out.
      </p>
    </>
  );
}

function Hero(): ReactNode {
  return (
    <header className={styles.hero}>
      <div className={styles.heroGrid}>
        <div className={styles.heroText}>
          <span className={styles.eyebrow}>Universitat Politècnica de Catalunya · DTAK</span>
          <h1 className={styles.title}>
            Query data sources
            <br />
            that were never designed to <span className={styles.titleAccent}>fit together</span>.
          </h1>
          <p className={styles.subtitle}>
            Aran reads your files, databases, object storage, Iceberg catalogs and Singer taps.
            It derives a schema for each, proposes how they join, and gives you one versioned
            vocabulary to query. Self-hosted, with one Docker Compose file.
          </p>
          <div className={styles.actions}>
            <Link className="button button--primary button--lg" to="/docs/getting-started/quickstart">
              Get started
            </Link>
            <Link className="button button--secondary button--lg" to="/docs/overview/what-is-aran">
              What is Aran?
            </Link>
            <Link
              className="button button--secondary button--lg"
              href="https://github.com/dtak-upc/aran">
              GitHub
            </Link>
          </div>
        </div>
        <div className={styles.heroVisual}>
          <IntegrationDiagram />
        </div>
      </div>
    </header>
  );
}

function Capabilities(): ReactNode {
  return (
    <section className={styles.section}>
      <div className={styles.sectionInner}>
        <h2 className={styles.sectionTitle}>Five capabilities, one pipeline</h2>
        <p className={styles.sectionLead}>
          Each stage feeds the next. The integration is stored as a graph you can query and
          version, so it lives in one place instead of in transformation code.{' '}
          <Link to="/docs/getting-started/walkthrough">The walkthrough</Link> runs all five on
          three real datasets.
        </p>
        <div className={styles.grid}>
          {CAPABILITIES.map((c, i) => (
            <div key={c.title} className={styles.card}>
              <span className={styles.cardIcon}>{c.icon}</span>
              <h3 className={styles.cardTitle}>
                {i + 1}. {c.title}
              </h3>
              <p className={styles.cardBody}>{c.body}</p>
              <Link to={c.to}>Learn more →</Link>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function QueryLanguage(): ReactNode {
  return (
    <section className={styles.section}>
      <div className={clsx(styles.sectionInner, styles.split)}>
        <div>
          <h2 className={styles.sectionTitle}>OQL</h2>
          <p className={styles.sectionLead}>
            A join names a relationship instead of matching columns. The planner reads the join
            keys from the integrated schema, compiles the query with Apache Calcite and runs it
            on DuckDB.
          </p>
          <Link to="/docs/oql/tutorial">Write your first query →</Link>
        </div>
        <div>
          <CodeBlock language="sql" title="Papers by UPC researchers, from two sources at once">
            {OQL_SAMPLE}
          </CodeBlock>
          <p className={styles.codeCaption}>
            <code>IPaper</code> is one class over two unrelated paper databases, and the rows come
            back from both. The query names no table, file or source.
          </p>
        </div>
      </div>
    </section>
  );
}

function GetStarted(): ReactNode {
  return (
    <section className={styles.section}>
      <div className={clsx(styles.sectionInner, styles.split)}>
        <div>
          <h2 className={styles.sectionTitle}>Run it</h2>
          <p className={styles.sectionLead}>
            One Compose file starts the whole stack, and the first start prints a one-time
            administrator password. To develop Aran, <code>make all</code> starts everything
            from source.
          </p>
          <Link to="/docs/getting-started/quickstart">Quickstart →</Link>
        </div>
        <div>
          <CodeBlock language="bash">{QUICKSTART}</CodeBlock>
          <p className={styles.codeCaption}>
            Needs Docker 24 or later with Compose v2. Then open <code>localhost:8080</code> and
            sign in as <code>alice</code>.
          </p>
        </div>
      </div>
    </section>
  );
}

function DataRepositories(): ReactNode {
  return (
    <section className={styles.section}>
      <div className={styles.sectionInner}>
        <h2 className={styles.sectionTitle}>Sources</h2>
        <p className={styles.sectionLead}>
          A data repository is a registered connection to a place where data lives. The five
          types differ in what they can do, and{' '}
          <Link to="/docs/overview/status">the status page</Link> says how far each has been
          tested.
        </p>
        <div className={styles.matrixScroll}>
          <div className={styles.matrixWrap}>
            <div className={styles.matrixHeadRow}>
              <span className={styles.matrixHeadCell}>Type</span>
              <span className={styles.matrixHeadCell}>Connect</span>
              <span className={styles.matrixHeadCell}>Browse</span>
              <span className={styles.matrixHeadCell}>Import</span>
              <span className={styles.matrixHeadCell}>Live federated</span>
            </div>
            {REPOSITORIES.map((r) => (
              <div key={r.type} className={styles.matrixRow}>
                <span className={clsx(styles.matrixCell, styles.matrixType)}>
                  <Link to={r.href}>{r.type}</Link>
                </span>
                <span className={styles.matrixCell}>
                  <Dot level={r.connect} />
                  <span className={styles.matrixNote}>{r.connectNote}</span>
                </span>
                <span className={styles.matrixCell}>
                  <Dot level={r.browse} />
                  {r.browseNote && <span className={styles.matrixNote}>{r.browseNote}</span>}
                </span>
                <span className={styles.matrixCell}>
                  {r.importNote ? (
                    <span className={styles.matrixValue}>{r.importNote}</span>
                  ) : (
                    <span className={styles.matrixDash}>&mdash;</span>
                  )}
                </span>
                <span className={styles.matrixCell}>
                  {r.federatedNote ? (
                    <span className={styles.matrixValue}>{r.federatedNote}</span>
                  ) : (
                    <span className={styles.matrixDash}>&mdash;</span>
                  )}
                </span>
              </div>
            ))}
          </div>
        </div>
        <div className={styles.matrixLegend}>
          <span className={styles.matrixLegendItem}>
            <Dot level="full" /> Full
          </span>
          <span className={styles.matrixLegendItem}>
            <Dot level="none" /> Not supported
          </span>
          <Link className={styles.matrixFootLink} to="/docs/repositories/">
            Full capability reference →
          </Link>
        </div>
      </div>
    </section>
  );
}

function Closing(): ReactNode {
  return (
    <section className={styles.closing}>
      <h2 className={styles.sectionTitle}>Read the documentation</h2>
      <p className={styles.sectionLead} style={{margin: '0 auto 2rem'}}>
        Start with the quickstart, or read what Aran is and is not for.
      </p>
      <div className={styles.actions} style={{justifyContent: 'center'}}>
        <Link className="button button--primary button--lg" to="/docs/">
          Documentation
        </Link>
        <Link className="button button--secondary button--lg" to="/docs/contributing/">
          Contribute
        </Link>
      </div>
      <p className={styles.footnote}>
        Aran is pre-1.0 research software from the DTAK group at the Universitat Politècnica de
        Catalunya. Its schema-integration metamodel follows Flores et al. (2024), whose running
        example the test suite reproduces end to end.
      </p>
    </section>
  );
}

export default function Home(): ReactNode {
  const {siteConfig} = useDocusaurusContext();
  return (
    <Layout
      title={siteConfig.title}
      description="Aran derives a schema for each of your data sources, proposes how they join, and gives you one versioned vocabulary to query over JDBC, MCP or the web workbench.">
      <Hero />
      <main>
        <Capabilities />
        <QueryLanguage />
        <GetStarted />
        <DataRepositories />
        <Closing />
      </main>
    </Layout>
  );
}
