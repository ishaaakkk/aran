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
 * Kept deliberately short. Every claim on it is one the documentation backs up and
 * links to: there is no capability named here that the repository does not
 * implement, which is the same rule the docs themselves follow. In particular it
 * does not claim a licence, because the repository does not carry one.
 *
 * The hero leads with automated schema integration at scale, not with querying:
 * a query is one of several things the resulting graph makes possible (alongside
 * applications and agents), not the reason Aran exists.
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
    title: 'Sources stay where they are',
    body: 'Register a database, a bucket, a file, an endpoint. Aran derives a schema for each one by reading it, rather than asking you to describe it up front.',
    icon: <IconDatabase />,
  },
  {
    title: 'Integration is data, not code',
    body: 'How sources relate is declared as an RDF graph you can query, diff and version, instead of being buried in transformation code that nothing can inspect.',
    icon: <IconGraphNodes />,
  },
  {
    title: 'One query, many sources',
    body: 'Ask a question against the shared vocabulary. The engine unfolds it into the sources behind it and answers from all of them at once.',
    icon: <IconQuery />,
  },
];

const PIPELINE = [
  {title: 'Register', body: 'Connect a repository and bring a dataset in.'},
  {title: 'Bootstrap', body: 'A schema is derived from the data, with the SQL needed to read it back.'},
  {title: 'Profile', body: 'Column statistics feed both join discovery and the query optimiser.'},
  {title: 'Integrate', body: 'Map source properties onto a shared vocabulary; two sources can become one class.'},
  {title: 'Query', body: 'Write OQL against that vocabulary and get rows from every source.'},
];

const REPOSITORIES: RepoRow[] = [
  {
    type: 'Relational databases',
    href: '/docs/repositories/relational-databases',
    connect: 'full',
    connectNote: 'JDBC connect',
    browse: 'full',
    browseNote: 'table list',
    importNote: 'PostgreSQL only',
    federatedNote: 'PostgreSQL, MySQL, MariaDB',
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
  {label: 'Query', sub: 'OQL, SQL, or your BI tool', top: 54},
  {label: 'Applications', sub: 'build directly on the graph', top: 164},
  {label: 'Agents', sub: 'grounded context, not a prompt', top: 274},
];

const OQL_SAMPLE = `SELECT IPaper.title, IResearcher.name
FROM IPaper -[hasAuthor]-> IResearcher
WHERE IResearcher.affiliation = 'UPC'`;

const QUICKSTART = `git clone https://github.com/dtak-upc/aran
cd aran
make all`;

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
        Four different formats, one automatic pass. No transformation code written by hand.
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
            Sources that were never meant to meet,
            <br />
            integrated into <span className={styles.titleAccent}>one graph</span>.
          </h1>
          <p className={styles.subtitle}>
            Aran derives a schema from every source it registers (a database, a bucket, a
            file, an endpoint) and aligns those schemas into one graph, at a scale no team
            could map by hand. You can then query the graph, build an application on it, or
            hand it to an agent.
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
        <h2 className={styles.sectionTitle}>What it does</h2>
        <p className={styles.sectionLead}>
          Two organisations describing the same thing rarely describe it the same way, and
          neither will change for you. The usual answer is a pipeline of transformation code.
          Aran takes the other route.
        </p>
        <div className={styles.grid}>
          {CAPABILITIES.map((c) => (
            <div key={c.title} className={styles.card}>
              <span className={styles.cardIcon}>{c.icon}</span>
              <h3 className={styles.cardTitle}>{c.title}</h3>
              <p className={styles.cardBody}>{c.body}</p>
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
            A join is a traversal of a named relationship, not a condition on columns you had to
            look up. The planner takes the join keys from the model, compiles the query with
            Apache Calcite and executes it on DuckDB.
          </p>
          <Link to="/docs/oql/tutorial">Write your first query →</Link>
        </div>
        <div>
          <CodeBlock language="sql" title="Papers by UPC researchers, from two databases at once">
            {OQL_SAMPLE}
          </CodeBlock>
          <p className={styles.codeCaption}>
            <code>IPaper</code> is one class over two unrelated paper databases; the rows come
            back from both. Nothing in the query names a table, a file or a source.
          </p>
        </div>
      </div>
    </section>
  );
}

function Pipeline(): ReactNode {
  return (
    <section className={styles.section}>
      <div className={styles.sectionInner}>
        <h2 className={styles.sectionTitle}>How it works</h2>
        <p className={styles.sectionLead}>
          Five steps from a file nobody has looked at to a query that spans it.{' '}
          <Link to="/docs/getting-started/walkthrough">The walkthrough does all five</Link> with
          three real datasets.
        </p>
        <ol className={styles.pipelineRow}>
          {PIPELINE.map((s, i) => (
            <li
              key={s.title}
              className={clsx(styles.pipelineNode, i === PIPELINE.length - 1 && styles.pipelineNodeActive)}>
              <span className={styles.pipelineNum}>{i + 1}</span>
              <span className={styles.pipelineTitle}>{s.title}</span>
              <span className={styles.pipelineBody}>{s.body}</span>
            </li>
          )).reduce((acc: ReactNode[], node, i) => {
            if (i > 0) {
              acc.push(
                <li key={`arrow-${i}`} className={styles.pipelineArrow} aria-hidden="true">
                  <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M5 3 L11 8 L5 13" />
                  </svg>
                </li>,
              );
            }
            acc.push(node);
            return acc;
          }, [])}
        </ol>
        <ol className={styles.steps}>
          {PIPELINE.map((s) => (
            <li key={s.title} className={styles.step}>
              <span className={styles.stepTitle}>{s.title}</span>
              <span className={styles.stepBody}>{s.body}</span>
            </li>
          ))}
        </ol>
      </div>
    </section>
  );
}

function GetStarted(): ReactNode {
  return (
    <section className={styles.section}>
      <div className={clsx(styles.sectionInner, styles.split)}>
        <div>
          <h2 className={styles.sectionTitle}>Run it locally</h2>
          <p className={styles.sectionLead}>
            One command brings up the whole stack: the lake and its object store, the profiling
            sidecar, PostgreSQL, Keycloak, Fuseki, the backend and the web client. The first run
            builds a Python environment and takes a few minutes.
          </p>
          <Link to="/docs/getting-started/quickstart">Quickstart →</Link>
        </div>
        <div>
          <CodeBlock language="bash">{QUICKSTART}</CodeBlock>
          <p className={styles.codeCaption}>
            Needs JDK 25, Node 20+, Docker and Python 3.12. Sign in as <code>alice</code> /{' '}
            <code>aran</code>.
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
        <h2 className={styles.sectionTitle}>Data repositories</h2>
        <p className={styles.sectionLead}>
          A data repository is a registered connection to a place where data lives. Aran
          recognises five types, and they do not all support the same things. The table shows
          what each one does.
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
        Tutorials, how-to guides, reference and the architecture deep dives, organised by what
        you are trying to do.
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
        Aran is built at the Universitat Politècnica de Catalunya. Its schema-integration
        metamodel follows Flores et al. (2024), whose running example is reproduced end to end
        in the test suite.
      </p>
    </section>
  );
}

export default function Home(): ReactNode {
  const {siteConfig} = useDocusaurusContext();
  return (
    <Layout
      title={siteConfig.title}
      description="Aran automatically integrates heterogeneous data sources into one graph, at a scale no team could map by hand. Query it, build on it, or hand it to an agent.">
      <Hero />
      <main>
        <Capabilities />
        <QueryLanguage />
        <Pipeline />
        <GetStarted />
        <DataRepositories />
        <Closing />
      </main>
    </Layout>
  );
}
