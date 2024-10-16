package org.labkey.api.sequenceanalysis.run;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.exception.ConvergenceException;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.labkey.api.data.ConvertHelper;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.pipeline.PipelineJobService;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.sequenceanalysis.pipeline.SequencePipelineService;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by bimber on 8/8/2014.
 */
abstract public class AbstractGatkWrapper extends AbstractCommandWrapper
{
    protected Integer _minRamPerQueueJob = 2;
    protected Integer _maxRamOverride = null;

    public AbstractGatkWrapper(Logger log)
    {
        super(log);
    }

    protected String getJarName()
    {
        return "GenomeAnalysisTK.jar";
    }

    protected File getJAR()
    {
        String path = PipelineJobService.get().getConfigProperties().getSoftwarePackagePath("GATKPATH");
        if (path != null)
        {
            return new File(path);
        }

        path = PipelineJobService.get().getConfigProperties().getSoftwarePackagePath(SequencePipelineService.SEQUENCE_TOOLS_PARAM);
        if (path == null)
        {
            path = PipelineJobService.get().getAppProperties().getToolsDirectory();
        }

        return path == null ? new File(getJarName()) : new File(path, getJarName());
    }

    public void setMaxRamOverride(Integer maxRamOverride)
    {
        _maxRamOverride = maxRamOverride;
    }

    public boolean jarExists()
    {
        return getJAR() == null || !getJAR().exists();
    }

    protected void ensureDictionary(File referenceFasta) throws PipelineJobException
    {
        getLogger().info("\tensure fasta index exists");
        SequenceAnalysisService.get().ensureFastaIndex(referenceFasta, getLogger());

        getLogger().info("\tensure dictionary exists");
        new CreateSequenceDictionaryWrapper(getLogger()).execute(referenceFasta, false);
    }

    protected List<String> getBaseArgs()
    {
        List<String> args = new ArrayList<>();
        args.add(SequencePipelineService.get().getJava8FilePath());
        args.addAll(SequencePipelineService.get().getJavaOpts(_maxRamOverride));
        args.add("-jar");
        args.add(getJAR().getPath());

        return args;
    }
}
