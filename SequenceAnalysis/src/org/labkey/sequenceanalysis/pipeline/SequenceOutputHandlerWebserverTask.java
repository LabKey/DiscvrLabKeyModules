package org.labkey.sequenceanalysis.pipeline;

import org.labkey.api.data.Table;
import org.labkey.api.data.TableInfo;
import org.labkey.api.pipeline.AbstractTaskFactory;
import org.labkey.api.pipeline.AbstractTaskFactorySettings;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.pipeline.RecordedAction;
import org.labkey.api.pipeline.RecordedActionSet;
import org.labkey.api.sequenceanalysis.SequenceOutputFile;
import org.labkey.api.sequenceanalysis.pipeline.SequenceOutputHandler;
import org.labkey.api.util.FileType;
import org.labkey.sequenceanalysis.SequenceAnalysisSchema;
import org.labkey.sequenceanalysis.SequenceAnalysisServiceImpl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Created by bimber on 1/16/2015.
 */
public class SequenceOutputHandlerWebserverTask extends PipelineJob.Task<SequenceOutputHandlerWebserverTask.Factory>
{
    protected SequenceOutputHandlerWebserverTask(Factory factory, PipelineJob job)
    {
        super(factory, job);
    }

    public static class Factory extends AbstractTaskFactory<AbstractTaskFactorySettings, Factory>
    {
        public Factory()
        {
            super(SequenceOutputHandlerWebserverTask.class);
            setLocation("webserver-high-priority");
        }

        public List<FileType> getInputTypes()
        {
            return Collections.emptyList();
        }

        public String getStatusName()
        {
            return PipelineJob.TaskStatus.running.toString();
        }

        public List<String> getProtocolActionNames()
        {
            List<String> allowableNames = new ArrayList<>();
            for (SequenceOutputHandler handler : SequenceAnalysisServiceImpl.get().getFileHandlers())
            {
                allowableNames.add(handler.getName());
            }

            return allowableNames;
        }

        @Override
        public boolean isParticipant(PipelineJob job) throws IOException
        {
            if (job instanceof SequenceOutputHandlerJob)
            {
                if (!((SequenceOutputHandlerJob)job).getHandler().doRunLocal())
                {
                    job.getLogger().info("skipping local task");
                    return false;
                }
            }

            return super.isParticipant(job);
        }

        public PipelineJob.Task createTask(PipelineJob job)
        {
            return new SequenceOutputHandlerWebserverTask(this, job);
        }

        public boolean isJobComplete(PipelineJob job)
        {
            return false;
        }
    }

    private SequenceOutputHandlerJob getPipelineJob()
    {
        return (SequenceOutputHandlerJob)getJob();
    }

    public RecordedActionSet run() throws PipelineJobException
    {
        List<RecordedAction> actions = new ArrayList<>();

        SequenceOutputHandler handler = getPipelineJob().getHandler();
        List<SequenceOutputFile> outputsToCreate = new ArrayList<>();

        handler.getProcessor().processFilesOnWebserver(getJob(), getPipelineJob().getFiles(), getPipelineJob().getJsonParams(), getPipelineJob().getAnalysisDirectory(), actions, outputsToCreate);

        if (!outputsToCreate.isEmpty())
        {
            getJob().getLogger().info(outputsToCreate.size() + " to create");
            for (SequenceOutputFile o : outputsToCreate)
            {
                getPipelineJob().addOutputToCreate(o);
            }
        }

        return new RecordedActionSet(actions);
    }

}