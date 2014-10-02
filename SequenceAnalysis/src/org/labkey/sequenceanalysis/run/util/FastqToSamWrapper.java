package org.labkey.sequenceanalysis.run.util;

import htsjdk.samtools.util.FastqQualityFormat;
import htsjdk.samtools.util.QualityEncodingDetector;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.util.FileUtil;
import org.labkey.sequenceanalysis.util.FastqUtils;

import java.io.File;
import java.util.LinkedList;
import java.util.List;

/**
 * User: bimber
 * Date: 10/24/12
 * Time: 9:14 AM
 */
public class FastqToSamWrapper extends PicardWrapper
{
    private FastqQualityFormat _fastqEncoding = null;

    public FastqToSamWrapper(@Nullable Logger logger)
    {
        super(logger);
    }

    public File execute(File file, @Nullable File file2) throws PipelineJobException
    {
        getLogger().info("Converting FASTQ to SAM: " + file.getPath());
        getLogger().info("\tFastqToSam version: " + getVersion());

        execute(getParams(file, file2));
        File output = new File(getOutputDir(file), getOutputFilename(file));
        if (!output.exists())
        {
            throw new PipelineJobException("Output file could not be found: " + output.getPath());
        }

        return output;
    }

    protected File getJar()
    {
        return getPicardJar("FastqToSam.jar");
    }

    private List<String> getParams(File file, File file2) throws PipelineJobException
    {
        List<String> params = new LinkedList<>();
        params.add("java");
        params.add("-jar");
        params.add(getJar().getPath());

        params.add("FASTQ=" + file.getPath());
        if (file2 != null)
            params.add("FASTQ2=" + file2.getPath());

        FastqQualityFormat encoding = _fastqEncoding;
        if (encoding == null)
        {
            encoding = FastqUtils.inferFastqEncoding(file);
            if (encoding != null)
            {
                getLogger().info("\tInferred FASTQ encoding of file " + file.getName() + " was: " + encoding.name());
            }
            else
            {
                encoding = FastqQualityFormat.Standard;
                getLogger().warn("\tUnable to infer FASTQ encoding for file: " + file.getPath() + ", defaulting to " + encoding.name());
            }
        }

        params.add("QUALITY_FORMAT=" + encoding);
        params.add("SAMPLE_NAME=SAMPLE");
        params.add("OUTPUT=" + new File(getOutputDir(file), getOutputFilename(file)).getPath());

        return params;
    }

    public String getOutputFilename(File file)
    {
        return FileUtil.getBaseName(file) + ".sam";
    }

//    public void setFastqEncoding(FastqUtils.FASTQ_ENCODING fastqEncoding)
//    {
//        _fastqEncoding = fastqEncoding;
//    }
}