package dpf.sp.gpinf.indexer.process.task;

import java.io.IOException;
import java.io.StringReader;
import java.util.Date;

import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.lucene.document.Document;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;

import dpf.sp.gpinf.indexer.Configuration;
import dpf.sp.gpinf.indexer.IndexFiles;
import dpf.sp.gpinf.indexer.io.ParsingReader;
import dpf.sp.gpinf.indexer.parsers.IndexerDefaultParser;
import dpf.sp.gpinf.indexer.process.Manager;
import dpf.sp.gpinf.indexer.process.Worker;
import dpf.sp.gpinf.indexer.process.Worker.IdLenPair;
import dpf.sp.gpinf.indexer.util.IOUtil;
import dpf.sp.gpinf.indexer.util.IndexerContext;
import gpinf.dev.data.EvidenceFile;

public class IndexTask extends AbstractTask{

	Worker worker;
	Manager manager;
	
	public IndexTask(Worker worker){
		this.worker = worker;
		this.manager = worker.manager;
	}
	
	public void process(EvidenceFile evidence) throws IOException{
		
		if(!evidence.isToIndex()){
			if (evidence.isSubItem()) {
				if (!evidence.getFile().delete())
					System.out.println(new Date() + "\t[AVISO]\t" + worker.getName() + " Falha ao deletar " + evidence.getFile().getAbsolutePath());
			}
			return;
		}
			
		manager.updateLastId(evidence.getId());
		
		String textCache = evidence.getParsedTextCache();

		if (textCache != null) {
			Document doc;
			if (Configuration.indexFileContents)
				doc = FileDocument.Document(evidence, new StringReader(textCache), worker.df);
			else
				doc = FileDocument.Document(evidence, null, worker.df);
			
			worker.writer.addDocument(doc);
			manager.textSizes.add(new IdLenPair(evidence.getId(), textCache.length()));

		} else {
			
			Metadata metadata = getMetadata(evidence);
			ParseContext context = getTikaContext(evidence, evidence.isParsed());
			
			TikaInputStream tis = null;
			try {
				tis = evidence.getTikaStream();
			} catch (IOException e) {
				System.out.println(new Date() + "\t[ALERTA]\t" + Thread.currentThread().getName() + " Erro ao abrir: " + evidence.getPath() + " " + e.toString());
			}
			
			ParsingReader reader;
			if (Configuration.indexFileContents && tis != null && 
				(Configuration.indexUnallocated || !CarveTask.UNALLOCATED_MIMETYPE.equals(evidence.getMediaType())))
					reader = new ParsingReader(worker.autoParser, tis, metadata, context);
			else
					reader = null;

			Document doc = FileDocument.Document(evidence, reader, worker.df);
			int fragments = 0;
			
			//Indexa os arquivos dividindo-os em fragmentos devido
			//a alto uso de RAM pela lib de indexação com docs gigantes
			do {
				if (++fragments > 1) {
					manager.incSplits();
					if (fragments == 2)
						manager.remindSplitedDoc(evidence.getId());

					if (IndexFiles.getInstance().verbose)
						System.out.println(new Date() + "\t[INFO]\t" + Thread.currentThread().getName() + "  Dividindo texto de " + evidence.getPath());
				}

				worker.writer.addDocument(doc);

			} while (!Thread.currentThread().isInterrupted() && reader != null && reader.nextFragment());

			if (reader != null) {
				manager.textSizes.add(new IdLenPair(evidence.getId(), reader.getTotalTextSize()));
				reader.close2();

			} else {
				manager.textSizes.add(new IdLenPair(evidence.getId(), 0));
				IOUtil.closeQuietly(tis);
			}

		}

		
	}
	
	private Metadata getMetadata(EvidenceFile evidence){
		Metadata metadata = new Metadata();
		metadata.set(Metadata.RESOURCE_NAME_KEY, evidence.getName());
		metadata.set(Metadata.CONTENT_LENGTH, evidence.getLength().toString());
		metadata.set(IndexerDefaultParser.INDEXER_CONTENT_TYPE, evidence.getMediaType().toString());
		if (evidence.timeOut)
			metadata.set(IndexerDefaultParser.INDEXER_TIMEOUT, "true");
		return metadata;
	}
	
	private ParseContext getTikaContext(EvidenceFile evidence, final boolean parsed) {
		// DEFINE CONTEXTO: PARSING RECURSIVO, ETC
		ParseContext context = new ParseContext();
		context.set(Parser.class, worker.autoParser);
		IndexerContext indexerContext = new IndexerContext(evidence);
		context.set(IndexerContext.class, indexerContext);
		context.set(EvidenceFile.class, evidence);
		context.set(EmbeddedDocumentExtractor.class, new ExportFileTask(context) {
			@Override
			public boolean shouldParseEmbedded(Metadata arg0) {
				return !parsed;
			}
		});
		
		// Tratamento p/ acentos de subitens de ZIP
		ArchiveStreamFactory factory = new ArchiveStreamFactory();
		factory.setEntryEncoding("Cp850");
		context.set(ArchiveStreamFactory.class, factory);
					
		/*PDFParserConfig config = new PDFParserConfig();
		config.setExtractInlineImages(true);
		context.set(PDFParserConfig.class, config);
		*/
		
		return context;
	}
	
}
