/*
 * Copyright (c) 2015 LabKey Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.labkey.api.sequenceanalysis.pipeline;

import org.jetbrains.annotations.Nullable;
import org.labkey.api.sequenceanalysis.model.Readset;
import org.labkey.api.util.Pair;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * User: bimber
 * Date: 6/21/2014
 * Time: 7:54 AM
 */
public class DefaultPipelineStepOutput implements PipelineStepOutput
{
    private final List<Pair<File, String>> _inputs = new ArrayList<>();
    private final List<Pair<File, String>> _outputs = new ArrayList<>();
    private final List<File> _intermediateFiles = new ArrayList<>();
    private final List<PicardMetricsOutput> _picardMetricsFiles = new ArrayList<>();
    private final List<File> _deferredDeleteFiles = new ArrayList<>();
    private final List<SequenceOutput> _sequenceOutputs = new ArrayList<>();
    private final List<String> _commands = new ArrayList<>();

    public DefaultPipelineStepOutput()
    {

    }

    @Override
    public List<Pair<File, String>> getInputs()
    {
        return Collections.unmodifiableList(_inputs);
    }

    @Override
    public List<Pair<File, String>> getOutputs()
    {
        return Collections.unmodifiableList(_outputs);
    }

    @Override
    public List<File> getOutputsOfRole(String role)
    {
        List<File> ret = new ArrayList<>();
        for (Pair<File, String> pair : _outputs)
        {
            if (role.equals(pair.second))
            {
                ret.add(pair.first);
            }
        }

        return ret;
    }

    @Override
    public List<File> getIntermediateFiles()
    {
        return Collections.unmodifiableList(_intermediateFiles);
    }

    @Override
    public void removeIntermediateFile(File toRemove)
    {
        _intermediateFiles.remove(toRemove);
    }

    @Override
    public List<PicardMetricsOutput> getPicardMetricsFiles()
    {
        return Collections.unmodifiableList(_picardMetricsFiles);
    }

    @Override
    public List<File> getDeferredDeleteIntermediateFiles()
    {
        return Collections.unmodifiableList(_deferredDeleteFiles);
    }

    @Override
    public List<SequenceOutput> getSequenceOutputs()
    {
        return Collections.unmodifiableList(_sequenceOutputs);
    }

    @Override
    public void addSequenceOutput(File file, String label, String category, @Nullable Integer readsetId, @Nullable Integer analysisId, @Nullable Integer genomeId, @Nullable String description)
    {
        _intermediateFiles.remove(file);

        _sequenceOutputs.add(new SequenceOutput(file, label, category, readsetId, analysisId, genomeId, description));
    }

    private boolean existsAsOutput(File f)
    {
        for (SequenceOutput so  : _sequenceOutputs)
        {
            if (so.getFile() != null && so.getFile().equals(f))
            {
                return true;
            }
        }

        return false;
    }

    @Override
    public void addInput(File input, String role)
    {
        _inputs.add(Pair.of(input, role));
    }

    @Override
    public void addOutput(File output, String role)
    {
        _outputs.add(Pair.of(output, role));
    }

    @Override
    public void addIntermediateFile(File file)
    {
        addIntermediateFile(file, null);
    }

    @Override
    public void addIntermediateFile(File file, String role)
    {
        if (role != null)
            addOutput(file, role);

        if (!existsAsOutput(file))
        {
            _intermediateFiles.add(file);
        }
    }

    @Override
    public void addIntermediateFiles(Collection<File> files)
    {
        files.forEach(this::addIntermediateFile);
    }

    public void addPicardMetricsFile(Readset rs, File metricFile, File inputFile)
    {
        _picardMetricsFiles.add(new PipelineStepOutput.PicardMetricsOutput(metricFile, inputFile, rs.getRowId()));
    }

    public void addPicardMetricsFile(Readset rs, File metricFile, PicardMetricsOutput.TYPE type)
    {
        if (!metricFile.exists())
        {
            throw new IllegalArgumentException("File does not exist: " + metricFile.getPath());
        }

        _picardMetricsFiles.add(new PipelineStepOutput.PicardMetricsOutput(metricFile, type, rs.getRowId()));
    }

    public void addDeferredDeleteIntermediateFile(File file)
    {
        _deferredDeleteFiles.add(file);
    }

    @Override
    public List<String> getCommandsExecuted()
    {
        return _commands;
    }

    public void addCommandsExecuted(List<String> commands)
    {
        _commands.addAll(commands);
    }

    public void addCommandExecuted(String command)
    {
        _commands.add(command);
    }
}
