package org.labkey.singlecell.analysis;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.labkey.api.data.Container;
import org.labkey.api.module.Module;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.pipeline.RecordedAction;
import org.labkey.api.query.DetailsURL;
import org.labkey.api.security.User;
import org.labkey.api.sequenceanalysis.SequenceOutputFile;
import org.labkey.api.sequenceanalysis.model.Readset;
import org.labkey.api.sequenceanalysis.pipeline.AbstractResumer;
import org.labkey.api.sequenceanalysis.pipeline.PipelineStepCtx;
import org.labkey.api.sequenceanalysis.pipeline.PipelineStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.SequenceAnalysisJobSupport;
import org.labkey.api.sequenceanalysis.pipeline.SequenceOutputHandler;
import org.labkey.api.sequenceanalysis.pipeline.SequencePipelineService;
import org.labkey.api.sequenceanalysis.pipeline.ToolParameterDescriptor;
import org.labkey.api.singlecell.CellHashingService;
import org.labkey.api.singlecell.pipeline.AbstractSingleCellPipelineStep;
import org.labkey.api.singlecell.pipeline.SingleCellStep;
import org.labkey.api.util.FileType;
import org.labkey.api.util.FileUtil;
import org.labkey.api.view.ActionURL;
import org.labkey.api.writer.PrintWriters;
import org.labkey.singlecell.SingleCellModule;
import org.labkey.singlecell.pipeline.singlecell.AbstractCellMembraneStep;
import org.labkey.singlecell.pipeline.singlecell.PrepareRawCounts;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ProcessSingleCellHandler implements SequenceOutputHandler<SequenceOutputHandler.SequenceOutputProcessor>, SequenceOutputHandler.HasActionNames
{
    private static FileType LOUPE_TYPE = new FileType("cloupe", false);

    private Resumer _resumer;

    public ProcessSingleCellHandler()
    {

    }

    @Override
    public String getName()
    {
        return "Single Cell Processing";
    }

    @Override
    public String getDescription()
    {
        return "Run one or more tools to process 10x scRNA-seq Data";
    }

    @Override
    public String getButtonJSHandler()
    {
        return null;
    }

    @Override
    public ActionURL getButtonSuccessUrl(Container c, User u, List<Integer> outputFileIds)
    {
        return DetailsURL.fromString("/singlecell/singleCellProcessing.view?outputFileIds=" + StringUtils.join(outputFileIds, ";"), c).getActionURL();
    }

    @Override
    public Module getOwningModule()
    {
        return ModuleLoader.getInstance().getModule(SingleCellModule.class);
    }

    @Override
    public LinkedHashSet<String> getClientDependencies()
    {
        return null;
    }

    @Override
    public boolean useWorkbooks()
    {
        return true;
    }

    @Override
    public boolean doSplitJobs()
    {
        return true;
    }

    @Override
    public boolean canProcess(SequenceOutputFile f)
    {
        return f.getFile() != null && LOUPE_TYPE.isType(f.getFile());
    }

    @Override
    public boolean doRunRemote()
    {
        return true;
    }

    @Override
    public boolean doRunLocal()
    {
        return false;
    }

    @Override
    public SequenceOutputProcessor getProcessor()
    {
        return new Processor();
    }

    @Override
    public Collection<String> getAllowableActionNames()
    {
        Set<String> allowableNames = new HashSet<>();
        for (PipelineStepProvider provider: SequencePipelineService.get().getProviders(SingleCellStep.class))
        {
            allowableNames.add(provider.getLabel());
        }

        allowableNames.add(PrepareRawCounts.LABEL);

        return allowableNames;
    }

    public class Processor implements SequenceOutputProcessor
    {
        @Override
        public void init(JobContext ctx, List<SequenceOutputFile> inputFiles, List<RecordedAction> actions, List<SequenceOutputFile> outputsToCreate) throws UnsupportedOperationException, PipelineJobException
        {
            boolean requiresHashingOrCite = false;
            List<PipelineStepCtx<SingleCellStep>> steps = SequencePipelineService.get().getSteps(ctx.getJob(), SingleCellStep.class);
            for (PipelineStepCtx<SingleCellStep> stepCtx : steps)
            {
                SingleCellStep step = stepCtx.getProvider().create(ctx);
                step.init(ctx, inputFiles);

                if (step.requiresHashingOrCiteSeq())
                {
                    requiresHashingOrCite = true;
                }

                for (ToolParameterDescriptor pd : stepCtx.getProvider().getParameters())
                {
                    if (pd instanceof ToolParameterDescriptor.CachableParam)
                    {
                        ctx.getLogger().debug("caching params for : " + pd.getName());
                        Object val = pd.extractValue(ctx.getJob(), stepCtx.getProvider(), stepCtx.getStepIdx(), Object.class);
                        ((ToolParameterDescriptor.CachableParam)pd).doCache(ctx.getJob(), val, ctx.getSequenceSupport());
                    }
                }
            }

            if (requiresHashingOrCite)
            {
                CellHashingService.get().prepareHashingAndCiteSeqFilesIfNeeded(ctx.getSourceDirectory(), ctx.getJob(), ctx.getSequenceSupport(), "readsetId", false, false, false);
            }
        }

        @Override
        public void processFilesOnWebserver(PipelineJob job, SequenceAnalysisJobSupport support, List<SequenceOutputFile> inputFiles, JSONObject params, File outputDir, List<RecordedAction> actions, List<SequenceOutputFile> outputsToCreate) throws UnsupportedOperationException, PipelineJobException
        {

        }

        @Override
        public void processFilesRemote(List<SequenceOutputFile> inputFiles, JobContext ctx) throws UnsupportedOperationException, PipelineJobException
        {
            _resumer = Resumer.create(ctx);

            Map<Integer, SequenceOutputFile> inputMap = inputFiles.stream().collect(Collectors.toMap(SequenceOutputFile::getRowid, so -> so));

            List<PipelineStepCtx<SingleCellStep>> steps = SequencePipelineService.get().getSteps(ctx.getJob(), SingleCellStep.class);

            String basename;
            if (inputFiles.size() == 1 && inputFiles.get(0).getReadset() != null)
            {
                Integer readsetId = inputFiles.get(0).getReadset();
                Readset rs = ctx.getSequenceSupport().getCachedReadset(readsetId);
                basename = FileUtil.makeLegalName(rs.getName());
            }
            else
            {
                basename = "SingleCellProcessing";
            }

            List<SingleCellStep.SeuratObjectWrapper> currentFiles;

            // Step 1: read raw data
            PrepareRawCounts prepareRawCounts = new PrepareRawCounts.Provider().create(ctx);
            ctx.getLogger().info("Starting to run: " + prepareRawCounts.getProvider().getLabel());
            if (_resumer.isStepComplete(0))
            {
                ctx.getLogger().info("resuming from saved state");
                currentFiles = _resumer.getSeuratFromStep(0);
            }
            else
            {
                RecordedAction action = new RecordedAction(prepareRawCounts.getProvider().getLabel());
                Date start = new Date();
                action.setStartTime(start);

                List<SingleCellStep.SeuratObjectWrapper> inputMatrices = new ArrayList<>();
                inputFiles.forEach(x -> {
                    String datasetName = ctx.getSequenceSupport().getCachedReadset(x.getReadset()).getName();
                    File source = new File(x.getFile().getParentFile(), "raw_feature_bc_matrix");
                    if (!source.exists())
                    {
                        throw new IllegalArgumentException("Unable to find file: " + source.getPath());
                    }

                    try
                    {
                        File localCopy = new File(ctx.getOutputDir(), x.getRowid() + "_RawData");
                        if (localCopy.exists())
                        {
                            ctx.getLogger().debug("Deleting directory: " + localCopy.getPath());
                            FileUtils.deleteDirectory(localCopy);
                        }

                        ctx.getLogger().debug("Copying raw data directory: " + source.getPath());
                        ctx.getLogger().debug("To: " + localCopy.getPath());

                        FileUtils.copyDirectory(source, localCopy);
                        _resumer.getFileManager().addIntermediateFile(localCopy);

                        inputMatrices.add(new SingleCellStep.SeuratObjectWrapper(String.valueOf(x.getRowid()), datasetName, localCopy, x));
                    }
                    catch (IOException e)
                    {
                        throw new IllegalArgumentException(e);
                    }
                });

                inputFiles.forEach(currentFile -> action.addInput(currentFile.getFile(), "Input Loupe File"));

                SingleCellStep.Output output0 = prepareRawCounts.execute(ctx, inputMatrices, basename + ".rawCounts");
                currentFiles =  output0.getSeuratObjects();
                if (currentFiles == null || currentFiles.isEmpty())
                {
                    throw new PipelineJobException("No seurat objects produced by: " + prepareRawCounts.getProvider().getName());
                }
                currentFiles.stream().map(SingleCellStep.SeuratObjectWrapper::getFile).forEach(_resumer.getFileManager()::addIntermediateFile);

                _resumer.setStepComplete(ctx.getLogger(), prepareRawCounts, 0, action, output0.getSeuratObjects(), output0.getMarkdownFile(), output0.getHtmlFile());
            }

            // Step 2: iterate seurat processing:
            String outputPrefix = basename;
            int stepIdx = 0;
            for (PipelineStepCtx<SingleCellStep> stepCtx : steps)
            {
                ctx.getLogger().info("Starting to run: " + stepCtx.getProvider().getLabel());
                ctx.getJob().setStatus(PipelineJob.TaskStatus.running, "Running: " + stepCtx.getProvider().getLabel());
                stepIdx++;

                SingleCellStep step = stepCtx.getProvider().create(ctx);
                step.setStepIdx(stepCtx.getStepIdx());

                if (!step.isIncluded(ctx, inputFiles))
                {
                    ctx.getLogger().info("Step not required, skipping");
                    continue;
                }

                if (_resumer.isStepComplete(stepIdx))
                {
                    ctx.getLogger().info("resuming from saved state");
                    if (_resumer.getSeuratFromStep(stepIdx) != null)
                    {
                        currentFiles = _resumer.getSeuratFromStep(stepIdx);
                    }
                    else if (step.createsSeuratObjects())
                    {
                        throw new PipelineJobException("Expected step to create seurat objects but none were cached");
                    }
                    else
                    {
                        ctx.getLogger().debug("No cached seurat objects found from step, using prior step's output");
                    }

                    continue;
                }

                currentFiles.forEach(currentFile -> {
                    if (currentFile.getSequenceOutputFileId() != null)
                    {
                        currentFile.setSequenceOutputFile(inputMap.get(currentFile.getSequenceOutputFileId()));
                    }
                });

                RecordedAction action = new RecordedAction(stepCtx.getProvider().getLabel());
                Date start = new Date();
                action.setStartTime(start);
                currentFiles.forEach(currentFile -> action.addInput(currentFile.getFile(), "Input Seurat Object"));
                ctx.getFileManager().addIntermediateFiles(currentFiles.stream().map(SingleCellStep.SeuratObjectWrapper::getFile).collect(Collectors.toList()));

                outputPrefix = outputPrefix + "." + step.getProvider().getName() + (step.getStepIdx() == 0 ? "" : "-" + step.getStepIdx());
                SingleCellStep.Output output = step.execute(ctx, currentFiles, outputPrefix);

                _resumer.getFileManager().addStepOutputs(action, output);

                if (output.getSeuratObjects() != null && !output.getSeuratObjects().isEmpty())
                {
                    currentFiles = new ArrayList<>(output.getSeuratObjects());
                    _resumer.getFileManager().addIntermediateFiles(currentFiles.stream().map(SingleCellStep.SeuratObjectWrapper::getFile).collect(Collectors.toSet()));
                    _resumer.getFileManager().addIntermediateFiles(currentFiles.stream().map(x -> SeuratCellHashingHandler.getCellBarcodesFromSeurat(x.getFile())).collect(Collectors.toSet()));
                }
                else if (step.createsSeuratObjects())
                {
                    throw new PipelineJobException("Expected step to create seurat objects but none reported");
                }
                else
                {
                    ctx.getLogger().info("No seurat objects were produced");
                }

                Date end = new Date();
                action.setEndTime(end);
                ctx.getJob().getLogger().info(stepCtx.getProvider().getLabel() + " Duration: " + DurationFormatUtils.formatDurationWords(end.getTime() - start.getTime(), true, true));

                _resumer.setStepComplete(ctx.getLogger(), step, stepIdx, action, currentFiles, output.getMarkdownFile(), output.getHtmlFile());
            }

            for (SingleCellStep.SeuratObjectWrapper seurat : currentFiles)
            {
                if (seurat.getFile().exists())
                {
                    ctx.getLogger().debug("Removing intermediate file: " + seurat.getFile().getPath());
                    ctx.getFileManager().removeIntermediateFile(seurat.getFile());
                    _resumer.getFileManager().removeIntermediateFile(seurat.getFile());
                    _resumer.getFileManager().removeIntermediateFile(SeuratCellHashingHandler.getCellBarcodesFromSeurat(seurat.getFile()));
                }
            }

            if (_resumer.getMarkdowns().isEmpty())
            {
                throw new PipelineJobException("No markdown files produced!");
            }

            //process with pandoc
            ctx.getJob().setStatus(PipelineJob.TaskStatus.running, "Creating final report");
            AbstractSingleCellPipelineStep.Markdown finalMarkdown = new AbstractSingleCellPipelineStep.Markdown();
            finalMarkdown.headerYml = finalMarkdown.getDefaultHeader();
            finalMarkdown.chunks = new ArrayList<>();
            for (File markdown : _resumer.getMarkdownsInOrder())
            {
                finalMarkdown.chunks.add(new AbstractSingleCellPipelineStep.Chunk(null, null, null, Collections.emptyList(), "child='" + markdown.getName() + "'"));

                //TODO: add markdown:
                //_resumer.getFileManager().addIntermediateFile(markdown);
            }
            finalMarkdown.chunks.add(new AbstractSingleCellPipelineStep.SessionInfoChunk());

            File finalMarkdownFile = new File(ctx.getOutputDir(), "final.rmd");
            try (PrintWriter writer = PrintWriters.getPrintWriter(finalMarkdownFile))
            {
                finalMarkdown.print(writer);
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }

            File finalHtml = new File(ctx.getOutputDir(), "finalHtml.html");
            List<String> lines = new ArrayList<>();
            lines.add("rmarkdown::render(output_file = '" + finalHtml.getName() + "', input = '" + finalMarkdownFile.getName() + "', intermediates_dir  = '/work')");
            AbstractSingleCellPipelineStep.executeR(ctx, AbstractCellMembraneStep.CONTINAER_NAME, "pandoc", lines);
            _resumer.getFileManager().addIntermediateFile(finalMarkdownFile);

            for (SingleCellStep.SeuratObjectWrapper output : currentFiles)
            {
                SequenceOutputFile so = new SequenceOutputFile();
                so.setName(output.getDatasetName() == null ? output.getDatasetId() : output.getDatasetName());
                so.setCategory("Seurat Object");
                so.setFile(output.getFile());

                if (NumberUtils.isCreatable(output.getDatasetId()))
                {
                    try
                    {
                        Integer id = NumberUtils.createInteger(output.getDatasetId());
                        if (!inputMap.containsKey(id))
                        {
                            ctx.getLogger().warn("No input found matching dataset Id: " + output.getDatasetId());
                        }
                        else
                        {
                            SequenceOutputFile o1 = inputMap.get(id);
                            so.setLibrary_id(o1.getLibrary_id());
                            so.setReadset(o1.getReadset());
                        }
                    }
                    catch (NumberFormatException e)
                    {
                        ctx.getLogger().error("Expected dataset ID to be an integer: " + output.getDatasetId());
                    }
                }

                _resumer.getFileManager().addSequenceOutput(so);

                // This could be a little confusing, but add one record out seurat output, even though there is one HTML file::
                SequenceOutputFile o = new SequenceOutputFile();
                o.setCategory("Seurat Report");
                o.setFile(finalHtml);
                o.setLibrary_id(so.getLibrary_id());
                o.setReadset(so.getReadset());
                o.setName("Seurat Report: " + so.getName());

                _resumer.getFileManager().addSequenceOutput(o);
            }

            _resumer.markComplete(ctx);
        }
    }

    private static class Resumer extends AbstractResumer
    {
        public static final String JSON_NAME = "processSingleCellCheckpoint.json";

        private Map<Integer, File> _markdowns = new HashMap<>();
        private Map<Integer, File> _htmlFiles = new HashMap<>();
        private Map<Integer, List<SingleCellStep.SeuratObjectWrapper>> _stepOutputs = new HashMap<>();

        @Override
        protected String getJsonName()
        {
            return JSON_NAME;
        }

        //for serialization
        public Resumer()
        {

        }

        private Resumer(JobContext ctx)
        {
            super(ctx.getSourceDirectory(), ctx.getLogger(), ctx.getFileManager());
        }

        public static Resumer create(JobContext ctx) throws PipelineJobException
        {
            Resumer ret;
            File json = getSerializedJson(ctx.getSourceDirectory(), JSON_NAME);
            if (!json.exists())
            {
                ret = new Resumer(ctx);
            }
            else
            {
                ret = readFromJson(json, Resumer.class);
                ret._isResume = true;
                ret.setLogger(ctx.getLogger());
                ret.setLocalWorkDir(ctx.getWorkDir().getDir());
                ret._fileManager.onResume(ctx.getJob(), ctx.getWorkDir());

            }

            return ret;
        }

        public boolean isStepComplete(int stepIdx)
        {
            return _stepOutputs.containsKey(stepIdx);
        }

        public List<SingleCellStep.SeuratObjectWrapper> getSeuratFromStep(int stepIdx)
        {
            return _stepOutputs.get(stepIdx);
        }

        public void setStepComplete(Logger log, SingleCellStep step, int stepIdx, RecordedAction action, List<SingleCellStep.SeuratObjectWrapper> seurat, File markdown, File html) throws PipelineJobException
        {
            log.info("Marking step complete: " + step.getProvider().getName());

            if (seurat != null)
            {
                seurat.forEach(x -> action.addOutput(x.getFile(), "Seurat Object", false));
            }

            action.addOutput(markdown, "Markdown File", true);
            action.addOutput(html, "HTML Report", false);

            _stepOutputs.put(stepIdx, seurat);
            _markdowns.put(stepIdx, markdown);
            _htmlFiles.put(stepIdx, html);

            _recordedActions.add(action);
            saveState();
        }

        public void setMarkdowns(Map<Integer, File> markdowns)
        {
            _markdowns = markdowns;
        }

        public void setHtmlFiles(Map<Integer, File> htmlFiles)
        {
            _htmlFiles = htmlFiles;
        }

        public Map<Integer, File> getMarkdowns()
        {
            return _markdowns;
        }

        public Map<Integer, File> getHtmlFiles()
        {
            return _htmlFiles;
        }

        public List<File> getMarkdownsInOrder()
        {
            return _markdowns.keySet().stream().sorted().map(_markdowns::get).collect(Collectors.toList());
        }

        public List<File> getHtmlFilesInOrder()
        {
            return _markdowns.keySet().stream().sorted().map(_markdowns::get).collect(Collectors.toList());
        }

        public Map<Integer, List<SingleCellStep.SeuratObjectWrapper>> getStepOutputs()
        {
            return _stepOutputs;
        }

        public void setStepOutputs(Map<Integer, List<SingleCellStep.SeuratObjectWrapper>> stepOutputs)
        {
            _stepOutputs = stepOutputs;
        }
    }

    public static class TestCase extends Assert
    {
        private static final Logger _log = LogManager.getLogger(ProcessSingleCellHandler.TestCase.class);

        @Test
        public void serializeTest() throws Exception
        {
            ProcessSingleCellHandler.Resumer r = new ProcessSingleCellHandler.Resumer();
            r.setLogger(_log);
            r.setRecordedActions(new LinkedHashSet<>());
            r.setFileManager(SequencePipelineService.get().getTaskFileManager());
            RecordedAction action1 = new RecordedAction();
            action1.setName("Action1");
            action1.setDescription("Description");
            action1.addInput(new File("/input"), "Input");
            action1.addOutput(new File("/output"), "Output", false);
            r.getRecordedActions().add(action1);

            r._markdowns.put(1, new File("file1"));
            r._markdowns.put(2, new File("file2"));

            SequenceOutputFile so1 = new SequenceOutputFile();
            so1.setRowid(999);
            r._stepOutputs.put(1, Arrays.asList(new SingleCellStep.SeuratObjectWrapper("datasetId", "datasetName", new File("seurat.rds"), so1)));

            File tmp = new File(System.getProperty("java.io.tmpdir"));
            File f = FileUtil.getAbsoluteCaseSensitiveFile(new File(tmp, ProcessSingleCellHandler.Resumer.JSON_NAME));

            SequenceOutputFile so = new SequenceOutputFile();
            so.setName("so1");
            so.setFile(f);
            r.getFileManager().addSequenceOutput(so);

            r.writeToJson(tmp);

            //after deserialization the RecordedAction should match the original
            ProcessSingleCellHandler.Resumer r2 = ProcessSingleCellHandler.Resumer.readFromJson(f, ProcessSingleCellHandler.Resumer.class);
            assertEquals(1, r2.getRecordedActions().size());
            RecordedAction action2 = r2.getRecordedActions().iterator().next();
            assertEquals("Action1", action2.getName());
            assertEquals("Description", action2.getDescription());
            assertEquals(1, action2.getInputs().size());
            assertEquals(new File("/input").toURI(), action1.getInputs().iterator().next().getURI());
            assertEquals(1, action2.getOutputs().size());
            assertEquals(new File("/output").toURI(), action2.getOutputs().iterator().next().getURI());
            assertEquals(1, r2.getFileManager().getOutputsToCreate().size());
            assertEquals("so1", r2.getFileManager().getOutputsToCreate().iterator().next().getName());
            assertEquals(f, r2.getFileManager().getOutputsToCreate().iterator().next().getFile());

            assertEquals("file1", r2.getMarkdowns().get(1).getName());
            assertEquals("file2", r2.getMarkdowns().get(2).getName());

            assertEquals(1, r2.getStepOutputs().size());
            assertEquals("datasetId", r2.getStepOutputs().get(1).get(0).getDatasetId());
            assertEquals("datasetName", r2.getStepOutputs().get(1).get(0).getDatasetName());
            assertEquals("seurat.rds", r2.getStepOutputs().get(1).get(0).getFile().getName());
            assertEquals(Integer.valueOf(999), r2.getStepOutputs().get(1).get(0).getSequenceOutputFileId());
            assertNull(r2.getStepOutputs().get(1).get(0).getSequenceOutputFile());

            f.delete();
        }
    }
}
