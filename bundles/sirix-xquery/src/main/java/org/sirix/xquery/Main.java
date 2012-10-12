package org.sirix.xquery;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Date;
import java.util.Random;

import org.brackit.xquery.QueryContext;
import org.brackit.xquery.QueryException;
import org.brackit.xquery.XQuery;
import org.brackit.xquery.xdm.Sequence;
import org.brackit.xquery.xdm.Store;
import org.sirix.xquery.node.DBStore;

public class Main {

	enum Severity {
		low, high, critical
	};

	public static void main(String[] args) {
		try {
			loadDocumentAndQuery();
			System.out.println();
			loadDocumentAndUpdate();
			System.out.println();
			loadCollectionAndQuery();
		} catch (IOException e) {
			System.err.print("I/O error: ");
			System.err.println(e.getMessage());
		} catch (QueryException e) {
			System.err.print("XQuery error ");
			System.err.print(e.getCode());
			System.err.print(": ");
			System.err.println(e.getMessage());
		}
	}

	private static void loadDocumentAndQuery() throws QueryException, IOException {
		// prepare sample document
		File tmpDir = new File(System.getProperty("java.io.tmpdir"));
		File doc = generateSampleDoc(tmpDir, "sample", 0);
		doc.deleteOnExit();

		// initialize query context and store
		final DBStore store = new DBStore();
		QueryContext ctx = new QueryContext(store);

		// use XQuery to load sample document into store
		System.out.println("Loading document:");
		String xq1 = String.format("bit:load('mydoc.xml', '%s')", doc);
		System.out.println(xq1);
		new XQuery(xq1).evaluate(ctx);

		// reuse store and query loaded document
		QueryContext ctx2 = new QueryContext(store);
		System.out.println();
		System.out.println("Query loaded document:");
		String xq2 = "doc('mydoc.xml')/log/@severity/string()";
		System.out.println(xq2);
		new XQuery(xq2).serialize(ctx2, System.out);
		System.out.println();
		store.close();
	}

	private static void loadDocumentAndUpdate() throws QueryException,
			IOException {
		// prepare sample document
		File tmpDir = new File(System.getProperty("java.io.tmpdir"));
		File doc = generateSampleDoc(tmpDir, "sample", 0);
		doc.deleteOnExit();

		// initialize query context and store
		try (final DBStore store = new DBStore().isUpdating(true)) {
			QueryContext ctx = new QueryContext();

			// use XQuery to load sample document into store
			System.out.println("Loading document:");
			String xq1 = String.format("bit:load('mydoc.xml', '%s')", doc);
			System.out.println(xq1);
			new XQuery(xq1).evaluate(ctx);

			// reuse store and query loaded document
			QueryContext ctx2 = new QueryContext(store);
			System.out.println();
			System.out.println("Query loaded document:");
			String xq2 = "insert nodes <a><b/></a> into doc('mydoc.xml')/log";
			System.out.println(xq2);
			// final Sequence seq = new XQuery(xq2).evaluate(ctx2);
			new XQuery(xq2).execute(ctx2);
			store.commitAll();
			System.out.println();
		}
	}

	private static void loadCollectionAndQuery() throws QueryException,
			IOException {
		// prepare directory with sample documents
		File tmpDir = new File(System.getProperty("java.io.tmpdir"));
		File dir = new File(tmpDir + File.separator + "docs"
				+ System.currentTimeMillis());
		if (!dir.mkdir()) {
			throw new IOException("Directory " + dir + " already exists");
		}
		dir.deleteOnExit();
		for (int i = 0; i < 10; i++) {
			generateSampleDoc(dir, "sample", i);
		}

		// initialize query context and store
		try (final DBStore store = new DBStore().isUpdating(true)) {
			QueryContext ctx = new QueryContext(store);

			// use XQuery to load all sample documents into store
			System.out.println("Load collection from files:");
			String xq1 = String.format(
					"bit:load('mydocs.col', io:ls('%s', '\\.xml$'))", dir);
			System.out.println(xq1);
			new XQuery(xq1).evaluate(ctx);

			// reuse store and query loaded collection
			QueryContext ctx2 = new QueryContext(store);
			System.out.println();
			System.out.println("Query loaded collection:");
			String xq2 = "for $log in collection('mydocs.col')/log\n"
					+ "where $log/@severity='critical'\n" + "return\n" + "<message>\n"
					+ "  <from>{$log/src/text()}</from>\n"
					+ "  <body>{$log/msg/text()}</body>\n" + "</message>\n";
			System.out.println(xq2);
			XQuery q = new XQuery(xq2);
			q.setPrettyPrint(true);
			q.serialize(ctx2, System.out);
			System.out.println();
		}
	}

	private static File generateSampleDoc(File dir, String prefix, int no)
			throws IOException {
		File file = File.createTempFile("sample", ".xml", dir);
		file.deleteOnExit();
		PrintStream out = new PrintStream(new FileOutputStream(file));
		Random rnd = new Random();
		long now = System.currentTimeMillis();
		int diff = rnd.nextInt(6000 * 60 * 24 * 7);
		Date tst = new Date(now - diff);
		Severity sev = Severity.values()[rnd.nextInt(3)];
		String src = "192.168." + (1 + rnd.nextInt(254)) + "."
				+ (1 + rnd.nextInt(254));
		int mlen = 10 + rnd.nextInt(70);
		byte[] bytes = new byte[mlen];
		int i = 0;
		while (i < mlen) {
			int wlen = 1 + rnd.nextInt(8);
			int j = i;
			while (j < Math.min(i + wlen, mlen)) {
				bytes[j++] = (byte) ('a' + rnd.nextInt('z' - 'a' + 1));
			}
			i = j;
			if (i < mlen - 1) {
				bytes[i++] = ' ';
			}
		}
		String msg = new String(bytes);
		out.print("<?xml version='1.0'?>");
		out.print(String.format("<log tstamp='%s' severity='%s'>", tst, sev));
		out.print(String.format("<src>%s</src>", src));
		out.print(String.format("<msg>%s</msg>", msg));
		out.print("</log>");
		out.close();
		return file;
	}

}
