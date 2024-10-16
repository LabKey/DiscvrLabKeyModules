package org.labkey.sequenceanalysis.pipeline;

import htsjdk.samtools.util.Interval;
import org.jetbrains.annotations.NotNull;
import org.labkey.api.pipeline.AbstractTaskFactory;
import org.labkey.api.pipeline.AbstractTaskFactorySettings;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.pipeline.RecordedActionSet;
import org.labkey.api.pipeline.WorkDirectoryTask;
import org.labkey.api.sequenceanalysis.pipeline.SequenceOutputHandler;
import org.labkey.api.util.FileType;
import org.labkey.sequenceanalysis.SequenceAnalysisServiceImpl;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VariantProcessingRemoteSplitTask extends WorkDirectoryTask<VariantProcessingRemoteSplitTask.Factory>
{
    protected VariantProcessingRemoteSplitTask(Factory factory, PipelineJob job)
    {
        super(factory, job);
    }

    public static class Factory extends AbstractTaskFactory<AbstractTaskFactorySettings, Factory>
    {
        public Factory()
        {
            super(VariantProcessingRemoteSplitTask.class);
        }

        @Override
        public List<FileType> getInputTypes()
        {
            return Collections.emptyList();
        }

        @Override
        public String getStatusName()
        {
            return PipelineJob.TaskStatus.running.toString();
        }

        @Override
        public List<String> getProtocolActionNames()
        {
            List<String> allowableNames = new ArrayList<>();
            for (SequenceOutputHandler handler : SequenceAnalysisServiceImpl.get().getFileHandlers(SequenceOutputHandler.TYPE.OutputFile))
            {
                allowableNames.add(handler.getName());

                if (handler instanceof SequenceOutputHandler.HasActionNames)
                {
                    allowableNames.addAll(((SequenceOutputHandler.HasActionNames)handler).getAllowableActionNames());
                }
            }

            return allowableNames;
        }

        @Override
        public boolean isJoin()
        {
            return false;
        }

        @Override
        public boolean isParticipant(PipelineJob job) throws IOException
        {
            return super.isParticipant(job);
        }

        @Override
        public PipelineJob.Task createTask(PipelineJob job)
        {
            return new VariantProcessingRemoteSplitTask(this, job);
        }

        @Override
        public boolean isJobComplete(PipelineJob job)
        {
            return false;
        }
    }

    private VariantProcessingJob getPipelineJob()
    {
        return (VariantProcessingJob)getJob();
    }

    @Override
    public @NotNull RecordedActionSet run() throws PipelineJobException
    {
        SequenceTaskHelper.logModuleVersions(getJob().getLogger());

        SequenceOutputHandler<SequenceOutputHandler.SequenceOutputProcessor> handler = getPipelineJob().getHandler();
        JobContextImpl ctx = new JobContextImpl(getPipelineJob(), getPipelineJob().getSequenceSupport(), getPipelineJob().getParameterJson(), _wd.getDir(), new TaskFileManagerImpl(getPipelineJob(), _wd.getDir(), _wd), _wd);

        List<Interval> intervals = getPipelineJob().getIntervalsForTask();
        if (intervals != null)
        {
            getJob().getLogger().info("Using intervals: " + getPipelineJob().getIntervalSetName() + ", total: " + intervals.size());
        }

        getJob().setStatus(PipelineJob.TaskStatus.running, "Running: " + handler.getName());
        handler.getProcessor().processFilesRemote(getPipelineJob().getFiles(), ctx);

        if (intervals != null)
        {
            if (handler instanceof SequenceOutputHandler.TracksVCF)
            {
                File output = ((SequenceOutputHandler.TracksVCF)handler).getScatterJobOutput(ctx);
                try
                {
                    output = ((SequenceOutputHandler.TracksVCF)handler).finalizeScatterJobOutput(ctx, output);

                    // If the output is still under the work dir, translate path. Otherwise it was already copied to the source dir
                    if (output == null)
                    {
                        ctx.getLogger().debug("No output produced, adding null to scatter outputs");
                        getPipelineJob().getScatterJobOutputs().put(getPipelineJob().getIntervalSetName(), null);
                    }
                    else if (output.getPath().startsWith(_wd.getDir().getPath()))
                    {
                        //NOTE: the VCF will be copied back to the source dir, so translate paths
                        String path = _wd.getRelativePath(output);
                        output = new File(ctx.getSourceDirectory(), path);
                        getPipelineJob().getScatterJobOutputs().put(getPipelineJob().getIntervalSetName(), output);
                    }
                    else
                    {
                        ctx.getLogger().debug("Output has already been moved from workdir: " + output.getPath());
                        getPipelineJob().getScatterJobOutputs().put(getPipelineJob().getIntervalSetName(), output);
                    }
                }
                catch (IOException e)
                {
                    throw new PipelineJobException(e);
                }

                if (output != null)
                {
                    getPipelineJob().getLogger().debug("Final scatter output: " + output.getPath());
                }
                else
                {
                    getPipelineJob().getLogger().debug("No primary scatter output produced");
                }
            }
            else
            {
                throw new PipelineJobException("Handler does not support TracksVCF: " + handler.getName());
            }
        }

        //Note: on job resume the TaskFileManager could be replaced with one from the resumer
        //Also, this needs to run after the step above to manage SequenceOutputFiles
        ctx.getFileManager().deleteIntermediateFiles();
        ctx.getFileManager().cleanup(ctx.getActions());

        return new RecordedActionSet(ctx.getActions());
    }
}
