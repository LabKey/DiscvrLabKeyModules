package org.labkey.singlecell.run;

import au.com.bytecode.opencsv.CSVReader;
import au.com.bytecode.opencsv.CSVWriter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;
import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.data.CompareType;
import org.labkey.api.data.ConvertHelper;
import org.labkey.api.data.DbSchema;
import org.labkey.api.data.DbSchemaType;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.Table;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.exp.api.ExpData;
import org.labkey.api.exp.api.ExperimentService;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.query.FieldKey;
import org.labkey.api.reader.Readers;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.sequenceanalysis.SequenceOutputFile;
import org.labkey.api.sequenceanalysis.model.AnalysisModel;
import org.labkey.api.sequenceanalysis.model.Readset;
import org.labkey.api.sequenceanalysis.pipeline.AbstractAlignmentStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.AlignerIndexUtil;
import org.labkey.api.sequenceanalysis.pipeline.AlignmentOutputImpl;
import org.labkey.api.sequenceanalysis.pipeline.AlignmentStep;
import org.labkey.api.sequenceanalysis.pipeline.AlignmentStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.CommandLineParam;
import org.labkey.api.sequenceanalysis.pipeline.IndexOutputImpl;
import org.labkey.api.sequenceanalysis.pipeline.PipelineContext;
import org.labkey.api.sequenceanalysis.pipeline.PipelineStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.ReferenceGenome;
import org.labkey.api.sequenceanalysis.pipeline.SequenceAnalysisJobSupport;
import org.labkey.api.sequenceanalysis.pipeline.SequencePipelineService;
import org.labkey.api.sequenceanalysis.pipeline.ToolParameterDescriptor;
import org.labkey.api.sequenceanalysis.run.AbstractAlignmentPipelineStep;
import org.labkey.api.sequenceanalysis.run.SimpleScriptWrapper;
import org.labkey.api.util.FileUtil;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.writer.PrintWriters;
import org.labkey.singlecell.SingleCellSchema;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CellRangerGexCountStep extends AbstractAlignmentPipelineStep<CellRangerWrapper> implements AlignmentStep
{
    public static final String LOUPE_CATEGORY = "10x Loupe File";

    public CellRangerGexCountStep(AlignmentStepProvider provider, PipelineContext ctx, CellRangerWrapper wrapper)
    {
        super(provider, ctx, wrapper);
    }

    public static class Provider extends AbstractAlignmentStepProvider<AlignmentStep>
    {
        public Provider()
        {
            super("CellRanger", "Cell Ranger is an alignment/analysis pipeline specific to 10x genomic data, and this can only be used on fastqs generated by 10x.", getCellRangerGexParams(null), PageFlowUtil.set("sequenceanalysis/field/GenomeFileSelectorField.js"), "https://support.10xgenomics.com/single-cell-gene-expression/software/pipelines/latest/what-is-cell-ranger", true, false, ALIGNMENT_MODE.MERGE_THEN_ALIGN);
        }

        @Override
        public String getName()
        {
            return "CellRanger";
        }

        @Override
        public String getDescription()
        {
            return null;
        }

        @Override
        public AlignmentStep create(PipelineContext context)
        {
            return new CellRangerGexCountStep(this, context, new CellRangerWrapper(context.getLogger()));
        }
    }

    public static List<ToolParameterDescriptor> getCellRangerGexParams(@Nullable List<ToolParameterDescriptor> additionalParams)
    {
        List<ToolParameterDescriptor> ret = Arrays.asList(
                ToolParameterDescriptor.create("id", "Run ID Suffix", "If provided, this will be appended to the ID of this run (readset name will be first).", "textfield", new JSONObject(){{
                    put("allowBlank", true);
                }}, null),
                ToolParameterDescriptor.createCommandLineParam(CommandLineParam.createSwitch("--nosecondary"), "nosecondary", "Skip Secondary Analysis", "Add this flag to skip secondary analysis of the gene-barcode matrix (dimensionality reduction, clustering and visualization). Set this if you plan to use cellranger reanalyze or your own custom analysis.", "checkbox", new JSONObject(){{

                }}, null),
                ToolParameterDescriptor.createCommandLineParam(CommandLineParam.create("--r1-length"), "r1-length", "R1 Read Length", "Use this value for the first read length.", "ldk-integerfield", new JSONObject(){{
                    put("minValue", 0);
                }}, null),
                ToolParameterDescriptor.createCommandLineParam(CommandLineParam.create("--r2-length"), "r2-length", "R2 Read Length", "Use this value for the second read length.", "ldk-integerfield", new JSONObject(){{
                    put("minValue", 0);
                }}, null),
                ToolParameterDescriptor.createCommandLineParam(CommandLineParam.create("--expect-cells"), "expect-cells", "Expect Cells", "Expected number of recovered cells.", "ldk-integerfield", new JSONObject(){{
                    put("minValue", 0);
                }}, 8000),
                ToolParameterDescriptor.createCommandLineParam(CommandLineParam.create("--force-cells"), "force-cells", "Force Cells", "Force pipeline to use this number of cells, bypassing the cell detection algorithm. Use this if the number of cells estimated by Cell Ranger is not consistent with the barcode rank plot.", "ldk-integerfield", new JSONObject(){{
                    put("minValue", 0);
                }}, null),
                ToolParameterDescriptor.createCommandLineParam(CommandLineParam.createSwitch("--disable-ui"), "disable-ui", "Disable UI", "If checked, this will run cellranger with the optional web-based UI disabled.", "checkbox", new JSONObject(){{
                    put("checked", true);
                }}, true),
                ToolParameterDescriptor.createExpDataParam("gtfFile", "Gene File", "This is the ID of a GTF file containing genes from this genome.", "sequenceanalysis-genomefileselectorfield", new JSONObject()
                {{
                    put("extensions", List.of("gtf"));
                    put("width", 400);
                    put("allowBlank", false);
                }}, null),
                ToolParameterDescriptor.createCommandLineParam(CommandLineParam.create("--chemistry"), "chemistry", "Chemistry", "This is usually left blank, in which case cellranger will auto-detect. Example values are: SC3Pv1, SC3Pv2, SC3Pv3, SC5P-PE, SC5P-R2, or SC5P-R1", "textfield", new JSONObject(){{

                }}, null),
                ToolParameterDescriptor.createCommandLineParam(CommandLineParam.createSwitch("--include-introns"), "includeIntrons", "Include Introns", "If selected, reads from introns will be included in the counts", "ldk-simplecombo", new JSONObject(){{
                    put("storeValues", "true;false");
                    put("value", "false");
                }}, null)
        );

        if (additionalParams != null)
        {
            ret = new ArrayList<>(ret);
            ret.addAll(additionalParams);
        }

        return ret;
    }

    @Override
    public boolean supportsGzipFastqs()
    {
        return true;
    }

    @Override
    public String getAlignmentDescription()
    {
        return getAlignDescription(getProvider(), getPipelineCtx(), getStepIdx(), true, getWrapper().getVersionString());
    }

    protected static String getAlignDescription(PipelineStepProvider<?> provider, PipelineContext ctx, int stepIdx, boolean addAligner, String cellrangerVersion)
    {
        Integer gtfId = provider.getParameterByName("gtfFile").extractValue(ctx.getJob(), provider, stepIdx, Integer.class);
        File gtfFile = ctx.getSequenceSupport().getCachedData(gtfId);
        if (gtfFile == null)
        {
            ExpData d = ExperimentService.get().getExpData(gtfId);
            if (d != null)
            {
                gtfFile = d.getFile();
            }
        }

        List<String> lines = new ArrayList<>();

        String includeIntrons = provider.getParameterByName("includeIntrons").extractValue(ctx.getJob(), provider, stepIdx, String.class, "false");
        lines.add("Include Introns: " + includeIntrons);

        if (addAligner)
        {
            lines.add("Aligner: " + provider.getName());
        }

        if (gtfFile != null)
        {
            lines.add("GTF: " + gtfFile.getName());
        }

        lines.add("Version: " + cellrangerVersion);

        return StringUtils.join(lines, '\n');
    }

    @Override
    public String getIndexCachedDirName(PipelineJob job)
    {
        Integer gtfId = getProvider().getParameterByName("gtfFile").extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Integer.class);
        if (gtfId == null)
        {
            throw new IllegalArgumentException("Missing gtfFile parameter");
        }

        return "cellRanger-" + gtfId;
    }

    @Override
    public IndexOutput createIndex(ReferenceGenome referenceGenome, File outputDir) throws PipelineJobException
    {
        //NOTE: GTF filtering typically only necessary for pseudogenes.  Assume this occurs upstream.
        //cellranger mkgtf hg19-ensembl.gtf hg19-filtered-ensembl.gtf --attribute=gene_biotype:protein_coding

        Integer gtfId = getProvider().getParameterByName("gtfFile").extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Integer.class);
        File gtfFile = getPipelineCtx().getSequenceSupport().getCachedData(gtfId);

        IndexOutputImpl output = new IndexOutputImpl(referenceGenome);

        File indexDir = new File(outputDir, getIndexCachedDirName(getPipelineCtx().getJob()));
        boolean hasCachedIndex = AlignerIndexUtil.hasCachedIndex(this.getPipelineCtx(), getIndexCachedDirName(getPipelineCtx().getJob()), referenceGenome);
        if (!hasCachedIndex)
        {
            getPipelineCtx().getLogger().info("Creating CellRanger Index");

            //remove if directory exists
            if (indexDir.exists())
            {
                try
                {
                    FileUtils.deleteDirectory(indexDir);
                }
                catch (IOException e)
                {
                    throw new PipelineJobException(e);
                }
            }

            output.addInput(gtfFile, "GTF File");

            //NOTE: cellranger requires lines to have transcript_id and gene_id.
            getPipelineCtx().getLogger().debug("Inspecting GTF for lines without gene_id or transcript_id");
            int linesDropped = 0;
            int exonsAdded = 0;

            File gtfEdit = new File(indexDir.getParentFile(), FileUtil.getBaseName(gtfFile) + ".geneId.gtf");

            try (CSVReader reader = new CSVReader(Readers.getReader(gtfFile), '\t', CSVWriter.NO_QUOTE_CHARACTER); CSVWriter writer = new CSVWriter(PrintWriters.getPrintWriter(gtfEdit), '\t', CSVWriter.NO_QUOTE_CHARACTER, CSVWriter.NO_ESCAPE_CHARACTER))
            {
                String[] line;
                while ((line = reader.readNext()) != null)
                {
                    if (!line[0].startsWith("#") && line.length < 9)
                    {
                        linesDropped++;
                        continue;
                    }

                    //Drop lines lacking gene_id/transcript, or with empty gene_id:
                    if (!line[0].startsWith("#") && (!line[8].contains("gene_id") || !line[8].contains("transcript_id") || line[8].contains("gene_id \"\"") || line[8].contains("transcript_id \"\"")))
                    {
                        linesDropped++;
                        continue;
                    }

                    writer.writeNext(line);
                }
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }

            if (linesDropped > 0)
            {
                getPipelineCtx().getLogger().info("dropped " + linesDropped + " lines lacking gene_id, transcript_id, or with an empty value for gene_id/transcript_id");
            }

            boolean useAlternateGtf = linesDropped > 0;
            if (useAlternateGtf)
            {
                gtfFile = gtfEdit;
            }
            else
            {
                getPipelineCtx().getLogger().debug("no need to drop lines from GTF");
                gtfEdit.delete();
            }

            List<String> args = new ArrayList<>();
            args.add(getWrapper().getExe().getPath());
            args.add("mkref");
            args.add("--fasta=" + referenceGenome.getWorkingFastaFile().getPath());
            args.add("--genes=" + gtfFile.getPath());
            args.add("--genome=" + indexDir.getName());

            Integer maxThreads = SequencePipelineService.get().getMaxThreads(getPipelineCtx().getLogger());
            if (maxThreads != null)
            {
                args.add("--nthreads=" + maxThreads);
            }

            Integer maxRam = SequencePipelineService.get().getMaxRam();
            if (maxRam != null)
            {
                args.add("--memgb=" + maxRam);
            }

            getWrapper().setWorkingDir(indexDir.getParentFile());
            getWrapper().execute(args);

            output.appendOutputs(referenceGenome.getWorkingFastaFile(), indexDir);

            //recache if not already
            AlignerIndexUtil.saveCachedIndex(hasCachedIndex, getPipelineCtx(), indexDir, getIndexCachedDirName(getPipelineCtx().getJob()), referenceGenome);

            if (useAlternateGtf)
            {
                gtfEdit.delete();
            }
        }

        return output;
    }

    @Override
    public boolean canAlignMultiplePairsAtOnce()
    {
        return true;
    }

    @Override
    public AlignmentOutput performAlignment(Readset rs, List<File> inputFastqs1, @Nullable List<File> inputFastqs2, File outputDirectory, ReferenceGenome referenceGenome, String basename, String readGroupId, @Nullable String platformUnit) throws PipelineJobException
    {
        AlignmentOutputImpl output = new AlignmentOutputImpl();

        String idParam = StringUtils.trimToNull(getProvider().getParameterByName("id").extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), String.class));
        String id = CellRangerWrapper.getId(idParam, rs);
        String alignmentMode = getProvider().getParameterByName(AbstractAlignmentStepProvider.ALIGNMENT_MODE_PARAM).extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx());
        AbstractAlignmentStepProvider.ALIGNMENT_MODE mode = AbstractAlignmentStepProvider.ALIGNMENT_MODE.valueOf(alignmentMode);

        List<Pair<File, File>> inputFastqs = new ArrayList<>();
        for (int i = 0; i < inputFastqs1.size();i++)
        {
            File inputFastq1 = inputFastqs1.get(i);
            File inputFastq2 = inputFastqs2.get(i);

            if (!inputFastq1.equals(rs.getReadData().get(0).getFile1()))
            {
                getPipelineCtx().getLogger().info("FASTQs appear to have been pre-processed, using local copies:");
                if (rs.getReadData().size() > 1)
                {
                    if (mode != AbstractAlignmentStepProvider.ALIGNMENT_MODE.MERGE_THEN_ALIGN)
                    {
                        throw new PipelineJobException("cellranger cannot be used with pre-processing unless MERGE_THEN_ALIGN is used");
                    }
                }
            }

            inputFastqs.add(Pair.of(inputFastq1, inputFastq2));
        }

        List<String> args = new ArrayList<>(getWrapper().prepareCountArgs(output, id, outputDirectory, rs, inputFastqs, getClientCommandArgs("="), true));

        Integer gtfId = getProvider().getParameterByName("gtfFile").extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Integer.class);
        File gtfFile = getPipelineCtx().getSequenceSupport().getCachedData(gtfId);
        output.addInput(gtfFile, CellRangerWrapper.GTF_FILE);

        File indexDir = AlignerIndexUtil.getIndexDir(referenceGenome, getIndexCachedDirName(getPipelineCtx().getJob()));
        args.add("--transcriptome=" + indexDir.getPath());

        boolean discardBam = getProvider().getParameterByName(AbstractAlignmentStepProvider.DISCARD_BAM).extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Boolean.class, false);
        args.add("--create-bam=" + !discardBam);

        getWrapper().setWorkingDir(outputDirectory);

        //Note: we can safely assume only this server is working on these files, so if the _lock file exists, it was from a previous failed job.
        File lockFile = new File(outputDirectory, id + "/_lock");
        if (lockFile.exists())
        {
            getPipelineCtx().getLogger().info("Lock file exists, deleting: " + lockFile.getPath());
            lockFile.delete();
        }

        getWrapper().execute(args);

        File outdir = new File(outputDirectory, id);
        outdir = new File(outdir, "outs");

        File bam = new File(outdir, "possorted_genome_bam.bam");
        if (!bam.exists())
        {
            throw new PipelineJobException("Unable to find file: " + bam.getPath());
        }
        output.setBAM(bam);

        getWrapper().deleteSymlinks(getWrapper().getLocalFastqDir(outputDirectory));

        try
        {
            String prefix = FileUtil.makeLegalName(rs.getName() + "_");
            File outputHtml = new File(outdir, "web_summary.html");
            if (!outputHtml.exists())
            {
                throw new PipelineJobException("Unable to find file: " + outputHtml.getPath());
            }

            File outputHtmlRename = new File(outdir, prefix + outputHtml.getName());
            if (outputHtmlRename.exists())
            {
                outputHtmlRename.delete();
            }
            FileUtils.moveFile(outputHtml, outputHtmlRename);
            String description = getAlignDescription(getProvider(), getPipelineCtx(), getStepIdx(), false, getWrapper().getVersionString());
            output.addSequenceOutput(outputHtmlRename, rs.getName() + " 10x Count Summary", "10x Run Summary", rs.getRowId(), null, referenceGenome.getGenomeId(), description);

            File loupe = new File(outdir, "cloupe.cloupe");
            if (loupe.exists())
            {
                File loupeRename = new File(outdir, prefix + loupe.getName());
                if (loupeRename.exists())
                {
                    loupeRename.delete();
                }
                FileUtils.moveFile(loupe, loupeRename);
                output.addSequenceOutput(loupeRename, rs.getName() + " 10x Loupe File", LOUPE_CATEGORY, rs.getRowId(), null, referenceGenome.getGenomeId(), description);
            }
            else
            {
                getPipelineCtx().getLogger().info("loupe file not found: " + loupe.getPath());
            }
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }

        //NOTE: this folder has many unnecessary files and symlinks that get corrupted when we rename the main outputs
        File directory = new File(outdir.getParentFile(), "SC_RNA_COUNTER_CS");
        if (directory.exists())
        {
            //NOTE: this will have lots of symlinks, including corrupted ones, which java handles badly
            new SimpleScriptWrapper(getPipelineCtx().getLogger()).execute(Arrays.asList("rm", "-Rf", directory.getPath()));
        }
        else
        {
            getPipelineCtx().getLogger().warn("Unable to find folder: " + directory.getPath());
        }

        return output;
    }

    @Override
    public boolean doAddReadGroups()
    {
        return false;
    }

    @Override
    public boolean doSortIndexBam()
    {
        return true;
    }

    @Override
    public boolean alwaysCopyIndexToWorkingDir()
    {
        return false;
    }

    @Override
    public void complete(SequenceAnalysisJobSupport support, AnalysisModel model, Collection<SequenceOutputFile> outputFilesCreated) throws PipelineJobException
    {
        File metrics = new File(model.getAlignmentFileObject().getParentFile(), "metrics_summary.csv");
        if (metrics.exists())
        {
            getPipelineCtx().getLogger().debug("adding 10x metrics");
            try (CSVReader reader = new CSVReader(Readers.getReader(metrics)))
            {
                String[] line;
                String[] header = null;
                String[] metricValues = null;

                int i = 0;
                while ((line = reader.readNext()) != null)
                {
                    if (i == 0)
                    {
                        header = line;
                    }
                    else
                    {
                        metricValues = line;
                        break;
                    }

                    i++;
                }

                if (model.getAlignmentFile() == null)
                {
                    throw new PipelineJobException("model.getAlignmentFile() was null");
                }

                TableInfo ti = DbSchema.get("sequenceanalysis", DbSchemaType.Module).getTable("quality_metrics");

                //NOTE: if this job errored and restarted, we may have duplicate records:
                SimpleFilter filter = new SimpleFilter(FieldKey.fromString("readset"), model.getReadset());
                filter.addCondition(FieldKey.fromString("analysis_id"), model.getRowId(), CompareType.EQUAL);
                filter.addCondition(FieldKey.fromString("dataid"), model.getAlignmentFile(), CompareType.EQUAL);
                filter.addCondition(FieldKey.fromString("category"), "Cell Ranger", CompareType.EQUAL);
                filter.addCondition(FieldKey.fromString("container"), getPipelineCtx().getJob().getContainer().getId(), CompareType.EQUAL);
                TableSelector ts = new TableSelector(ti, PageFlowUtil.set("rowid"), filter, null);
                if (ts.exists())
                {
                    getPipelineCtx().getLogger().info("Deleting existing QC metrics (probably from prior restarted job)");
                    ts.getArrayList(Integer.class).forEach(rowid -> {
                        Table.delete(ti, rowid);
                    });
                }

                for (int j = 0; j < header.length; j++)
                {
                    Map<String, Object> toInsert = new CaseInsensitiveHashMap<>();
                    toInsert.put("container", getPipelineCtx().getJob().getContainer().getId());
                    toInsert.put("createdby", getPipelineCtx().getJob().getUser().getUserId());
                    toInsert.put("created", new Date());
                    toInsert.put("readset", model.getReadset());
                    toInsert.put("analysis_id", model.getRowId());
                    toInsert.put("dataid", model.getAlignmentFile());

                    toInsert.put("category", "Cell Ranger");
                    toInsert.put("metricname", header[j]);

                    metricValues[j] = metricValues[j].replaceAll(",", "");
                    Object val = metricValues[j];
                    if (metricValues[j].contains("%"))
                    {
                        metricValues[j] = metricValues[j].replaceAll("%", "");
                        Double d = ConvertHelper.convert(metricValues[j], Double.class);
                        d = d / 100.0;
                        val = d;
                    }

                    toInsert.put("metricvalue", val);

                    Table.insert(getPipelineCtx().getJob().getUser(), ti, toInsert);
                }
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }
        }
        else
        {
            throw new PipelineJobException("unable to find metrics file: " + metrics.getPath());
        }

        TableInfo cDNA = SingleCellSchema.getInstance().getSchema().getTable(SingleCellSchema.TABLE_CDNAS);
        TableInfo singlecellDatasets = SingleCellSchema.getInstance().getSchema().getTable(SingleCellSchema.TABLE_SINGLECELL_DATASETS);
        for (SequenceOutputFile so : outputFilesCreated)
        {
            if (LOUPE_CATEGORY.equals(so.getCategory()))
            {
                getPipelineCtx().getLogger().debug("Creating singlecell dataset record");
                if (so.getRowid() == null || so.getRowid() == 0)
                {
                    throw new PipelineJobException("The outputfiles have not been created in the database yet");
                }

                Map<String, Object> toInsert = new HashMap<>();
                toInsert.put("loupeFileId", so.getRowid());
                toInsert.put("readsetId", so.getReadset());

                Readset rs = SequenceAnalysisService.get().getReadset(so.getReadset(), getPipelineCtx().getJob().getUser());
                toInsert.put("name", rs.getName());

                Integer maxCDNA = new TableSelector(cDNA, PageFlowUtil.set("rowid"), new SimpleFilter(FieldKey.fromString("readsetId"), rs.getReadsetId()), null).getArrayList(Integer.class).stream().max(Integer::compareTo).orElse(null);
                toInsert.put("cDNAId", maxCDNA);

                toInsert.put("container", so.getContainer());
                toInsert.put("created", so.getCreated());
                toInsert.put("createdby", so.getCreatedby());
                toInsert.put("modified", so.getModified());
                toInsert.put("modifiedby", so.getModifiedby());

                Table.insert(getPipelineCtx().getJob().getUser(), singlecellDatasets, toInsert);
            }
        }
    }

    private void addMetrics(File outDir, AnalysisModel model) throws PipelineJobException
    {
        getPipelineCtx().getLogger().debug("adding 10x metrics");

        File metrics = new File(outDir, "metrics_summary.csv");
        if (!metrics.exists())
        {
            throw new PipelineJobException("Unable to find file: " + metrics.getPath());
        }

        if (model.getAlignmentFile() == null)
        {
            throw new PipelineJobException("model.getAlignmentFile() was null");
        }

        try (CSVReader reader = new CSVReader(Readers.getReader(metrics)))
        {
            String[] line;
            List<String[]> metricValues = new ArrayList<>();

            int i = 0;
            while ((line = reader.readNext()) != null)
            {
                i++;
                if (i == 1)
                {
                    continue;
                }

                metricValues.add(line);
            }

            int totalAdded = 0;
            TableInfo ti = DbSchema.get("sequenceanalysis", DbSchemaType.Module).getTable("quality_metrics");

            //NOTE: if this job errored and restarted, we may have duplicate records:
            SimpleFilter filter = new SimpleFilter(FieldKey.fromString("readset"), model.getReadset());
            filter.addCondition(FieldKey.fromString("analysis_id"), model.getRowId(), CompareType.EQUAL);
            filter.addCondition(FieldKey.fromString("dataid"), model.getAlignmentFile(), CompareType.EQUAL);
            filter.addCondition(FieldKey.fromString("category"), "Cell Ranger VDJ", CompareType.EQUAL);
            filter.addCondition(FieldKey.fromString("container"), getPipelineCtx().getJob().getContainer().getId(), CompareType.EQUAL);
            TableSelector ts = new TableSelector(ti, PageFlowUtil.set("rowid"), filter, null);
            if (ts.exists())
            {
                getPipelineCtx().getLogger().info("Deleting existing QC metrics (probably from prior restarted job)");
                ts.getArrayList(Integer.class).forEach(rowid -> {
                    Table.delete(ti, rowid);
                });
            }

            for (String[] row : metricValues)
            {
                //TODO
                if ("Fastq ID".equals(row[2]) || "Physical library ID".equals(row[2]))
                {
                    continue;
                }

                Map<String, Object> toInsert = new CaseInsensitiveHashMap<>();
                toInsert.put("container", getPipelineCtx().getJob().getContainer().getId());
                toInsert.put("createdby", getPipelineCtx().getJob().getUser().getUserId());
                toInsert.put("created", new Date());
                toInsert.put("readset", model.getReadset());
                toInsert.put("analysis_id", model.getRowId());
                toInsert.put("dataid", model.getAlignmentFile());

                toInsert.put("category", "Cell Ranger");
                toInsert.put("metricname", row[4]);

                row[5] = row[5].replaceAll(",", ""); //remove commas
                Object val = row[5];
                if (row[5].contains("%"))
                {
                    row[5] = row[5].replaceAll("%", "");
                    Double d = ConvertHelper.convert(row[5], Double.class);
                    d = d / 100.0;
                    val = d;
                }

                toInsert.put("metricvalue", val);

                Table.insert(getPipelineCtx().getJob().getUser(), ti, toInsert);
                totalAdded++;
            }

            getPipelineCtx().getLogger().info("total metrics added: " + totalAdded);
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }
    }
}
