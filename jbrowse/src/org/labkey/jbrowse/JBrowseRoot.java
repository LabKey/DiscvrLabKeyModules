package org.labkey.jbrowse;

import htsjdk.samtools.BAMIndexer;
import htsjdk.samtools.SAMFileReader;
import htsjdk.samtools.ValidationStringency;
import htsjdk.samtools.util.BlockCompressedOutputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;
import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.CompareType;
import org.labkey.api.data.Container;
import org.labkey.api.data.ContainerManager;
import org.labkey.api.data.DbSchema;
import org.labkey.api.data.Results;
import org.labkey.api.data.Selector;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.Sort;
import org.labkey.api.data.Table;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.exp.api.ExpData;
import org.labkey.api.exp.api.ExperimentService;
import org.labkey.api.files.FileContentService;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.QueryService;
import org.labkey.api.security.User;
import org.labkey.api.sequenceanalysis.RefNtSequenceModel;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.services.ServiceRegistry;
import org.labkey.api.study.assay.AssayFileWriter;
import org.labkey.api.util.FileType;
import org.labkey.api.util.FileUtil;
import org.labkey.api.util.GUID;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.jbrowse.model.JsonFile;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * User: bimber
 * Date: 7/14/2014
 * Time: 4:09 PM
 */
public class JBrowseRoot
{
    private static final Logger _log = Logger.getLogger(JBrowseRoot.class);
    private Logger _customLogger = null;

    public JBrowseRoot(@Nullable Logger log)
    {
        _customLogger = log;
    }

    private Logger getLogger()
    {
        return _customLogger == null ? _log : _customLogger;
    }

    public File getBaseDir(Container c)
    {
        return getBaseDir(c, true);
    }

    public @Nullable File getBaseDir(Container c, boolean doCreate)
    {
        FileContentService fileService = ServiceRegistry.get().getService(FileContentService.class);
        File fileRoot = fileService == null ? null : fileService.getFileRoot(c, FileContentService.ContentType.files);
        if (fileRoot == null || !fileRoot.exists())
        {
            return null;
        }

        File jbrowseDir = new File(fileRoot, ".jbrowse");
        if (!jbrowseDir.exists())
        {
            if (!doCreate)
            {
                return null;
            }

            jbrowseDir.mkdirs();
        }

        return jbrowseDir;
    }

    public File getReferenceDir(Container c)
    {
        return new File(getBaseDir(c), "references");
    }

    public File getTracksDir(Container c)
    {
        return new File(getBaseDir(c), "tracks");
    }

    public File getDatabaseDir(Container c)
    {
        return new File(getBaseDir(c), "databases");
    }

    private boolean runScript(String scriptName, List<String> args) throws IOException
    {
        return runScript(scriptName, args, null);
    }

    private boolean runScript(String scriptName, List<String> args, @Nullable File workingDir) throws IOException
    {
        File scriptFile = new File(JBrowseManager.get().getJBrowseBinDir(), scriptName);
        if (!scriptFile.exists())
        {
            getLogger().error("Unable to find jbrowse script: " + scriptFile.getPath());
            return false;
        }

        args.add(0, "perl");
        args.add(1, scriptFile.getPath());

        getLogger().info("preparing jbrowse resource:");
        getLogger().info(StringUtils.join(args, " "));

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);

        if (workingDir != null)
        {
            getLogger().info("using working directory: " + workingDir.getPath());
            pb.directory(workingDir);
        }

        Process p = null;
        try
        {
            p = pb.start();
            try (BufferedReader procReader = new BufferedReader(new InputStreamReader(p.getInputStream())))
            {
                String line;
                while ((line = procReader.readLine()) != null)
                {
                    getLogger().debug(line);
                }
            }

            p.waitFor();
        }
        catch (Exception e)
        {
            getLogger().error(e.getMessage(), e);
            return false;
        }
        finally
        {
            if (p != null)
            {
                p.destroy();
            }
        }

        return true;
    }

    public JsonFile prepareOutputFile(Container c, User u, Integer outputFileId, boolean forceRecreateJson) throws IOException
    {
        //find existing resource
        TableInfo jsonFiles = DbSchema.get(JBrowseSchema.NAME).getTable(JBrowseSchema.TABLE_JSONFILES);
        TableSelector ts1 = new TableSelector(jsonFiles, new SimpleFilter(FieldKey.fromString("outputfile"), outputFileId), null);
        JsonFile jsonFile = null;
        if (ts1.exists())
        {
            jsonFile = ts1.getObject(JsonFile.class);
            if (!forceRecreateJson)
            {
                return jsonFile;
            }
        }

        //else create
        if (jsonFile == null)
        {
            TableInfo outputFiles = DbSchema.get("sequenceanalysis").getTable("outputfiles");
            TableSelector ts2 = new TableSelector(outputFiles, PageFlowUtil.set("container"), new SimpleFilter(FieldKey.fromString("rowid"), outputFileId), null);
            String containerId = ts2.getObject(String.class);
            Container fileContainer = ContainerManager.getForId(containerId);
            if (fileContainer == null)
            {
                throw new IOException("Unable to find container with Id: " + containerId);
            }

            Map<String, Object> jsonRecord = new CaseInsensitiveHashMap();
            jsonRecord.put("outputfile", outputFileId);
            File outDir = new File(getTracksDir(fileContainer), "track-" + outputFileId.toString());
            jsonRecord.put("relPath", FileUtil.relativePath(getBaseDir(fileContainer).getPath(), outDir.getPath()));
            jsonRecord.put("container", fileContainer.getId());
            jsonRecord.put("created", new Date());
            jsonRecord.put("createdby", u.getUserId());
            jsonRecord.put("modified", new Date());
            jsonRecord.put("modifiedby", u.getUserId());
            jsonRecord.put("objectid", new GUID().toString().toUpperCase());
            Table.insert(u, jsonFiles, jsonRecord);

            jsonFile = ts1.getObject(JsonFile.class);
        }

        return jsonFile;
    }

    public JsonFile prepareRefSeq(Container c, User u, Integer ntId, boolean forceRecreateJson) throws IOException
    {
        //validate
        TableInfo ti = QueryService.get().getUserSchema(u, c, JBrowseManager.SEQUENCE_ANALYSIS).getTable("ref_nt_sequences");
        TableSelector ts = new TableSelector(ti, new SimpleFilter(FieldKey.fromString("rowid"), ntId), null);
        RefNtSequenceModel model = ts.getObject(RefNtSequenceModel.class);

        if (model == null)
        {
            throw new IllegalArgumentException("Unable to find sequence: " + ntId);
        }

        //find existing resource
        TableInfo jsonFiles = DbSchema.get(JBrowseSchema.NAME).getTable(JBrowseSchema.TABLE_JSONFILES);
        TableSelector ts1 = new TableSelector(jsonFiles, new SimpleFilter(FieldKey.fromString("sequenceid"), ntId), null);
        JsonFile jsonFile = null;
        if (ts1.exists())
        {
            jsonFile = ts1.getObject(JsonFile.class);
            if (!forceRecreateJson)
            {
                return jsonFile;
            }
        }

        File outDir = new File(getReferenceDir(ContainerManager.getForId(model.getContainer())), ntId.toString());
        if (outDir.exists())
        {
            FileUtils.deleteDirectory(outDir);
        }
        outDir.mkdirs();

        //else create
        if (jsonFile == null)
        {
            Map<String, Object> jsonRecord = new CaseInsensitiveHashMap();
            jsonRecord.put("sequenceid", ntId);
            jsonRecord.put("relPath", FileUtil.relativePath(getBaseDir(c).getPath(), outDir.getPath()));
            jsonRecord.put("container", model.getContainer());
            jsonRecord.put("created", new Date());
            jsonRecord.put("createdby", u.getUserId());
            jsonRecord.put("modified", new Date());
            jsonRecord.put("modifiedby", u.getUserId());
            jsonRecord.put("objectid", new GUID().toString().toUpperCase());
            Table.insert(u, jsonFiles, jsonRecord);

            jsonFile = ts1.getObject(JsonFile.class);
        }

        AssayFileWriter afw = new AssayFileWriter();
        File fasta = afw.findUniqueFileName(model.getName() + ".fasta", outDir);
        fasta.createNewFile();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fasta)))
        {
            writer.write(">" + model.getName() + "\n");

            String seq = model.getSequence();
            int len = seq == null ? 0 : seq.length();
            int partitionSize = 60;
            for (int i=0; i<len; i+=partitionSize)
            {
                writer.write(seq.substring(i, Math.min(len, i + partitionSize)) + "\n");
            }
        }

        List<String> args = new ArrayList<>();

        args.add("--fasta");
        args.add(fasta.getPath());

        args.add("--trackLabel");
        args.add(model.getRowid() + "_" + model.getName());

        args.add("--key");
        args.add(model.getName());

        if (JBrowseManager.get().compressJSON())
        {
            args.add("--compress");
        }

        args.add("--out");
        args.add(outDir.getPath());

        boolean success = runScript("prepare-refseqs.pl", args);
        if (success)
        {
            fasta.delete();
        }

        File trackList = new File(outDir, "trackList.json");
        if (trackList.exists())
        {
            JSONObject obj = readFileToJson(trackList);
            String urlTemplate = obj.getJSONArray("tracks").getJSONObject(0).getString("urlTemplate");
            urlTemplate = "references/" + ntId.toString() + "/" + urlTemplate;
            obj.getJSONArray("tracks").getJSONObject(0).put("urlTemplate", urlTemplate);

            writeJsonToFile(trackList, obj.toString(1));
        }

        return jsonFile;
    }

    public void prepareDatabase(Container c, User u, String databaseId) throws IOException
    {
        File outDir = new File(getDatabaseDir(c), databaseId.toString());

        //Note: delete entire directory to ensure we recreate symlinks, etc.
        if (outDir.exists())
        {
            getLogger().info("deleting existing directory");
            FileUtils.deleteDirectory(outDir);
        }

        outDir.mkdirs();

        File seqDir = new File(outDir, "seq");
        seqDir.mkdirs();

        File trackDir = new File(outDir, "tracks");
        trackDir.mkdirs();

        TableInfo tableMembers = DbSchema.get(JBrowseSchema.NAME).getTable(JBrowseSchema.TABLE_DATABASE_MEMBERS);
        TableSelector ts = new TableSelector(tableMembers, new SimpleFilter(FieldKey.fromString("database"), databaseId), null);
        final List<String> jsonGuids = new ArrayList<>();
        ts.forEach(new Selector.ForEachBlock<ResultSet>()
        {
            @Override
            public void exec(ResultSet rs) throws SQLException
            {
                String jsonGuid = rs.getString("jsonfile");
                if (jsonGuid != null)
                {
                    jsonGuids.add(jsonGuid);
                }
            }
        });

        List<JsonFile> jsonFiles = new ArrayList<>();
        TableInfo tableJsonFiles = DbSchema.get(JBrowseSchema.NAME).getTable(JBrowseSchema.TABLE_JSONFILES);
        Sort sort = new Sort("sequenceid/name,trackid/name");
        TableSelector ts2 = new TableSelector(tableJsonFiles, new SimpleFilter(FieldKey.fromString("objectid"), jsonGuids, CompareType.IN), sort);
        jsonFiles.addAll(ts2.getArrayList(JsonFile.class));

        //also add library members:
        Integer libraryId = new TableSelector(JBrowseSchema.getInstance().getSchema().getTable(JBrowseSchema.TABLE_DATABASES), PageFlowUtil.set("libraryId"), new SimpleFilter(FieldKey.fromString("objectid"), databaseId), null).getObject(Integer.class);
        if (libraryId != null)
        {
            getLogger().info("adding library: " + libraryId);
            List<Integer> refNts = new TableSelector(DbSchema.get("sequenceanalysis").getTable("reference_library_members"), PageFlowUtil.set("ref_nt_id"), new SimpleFilter(FieldKey.fromString("library_id"), libraryId), null).getArrayList(Integer.class);

            getLogger().info("total ref sequences: " + refNts.size());
            for (Integer refNtId : refNts)
            {
                JsonFile f = prepareRefSeq(c, u, refNtId, false);
                if (f != null && !jsonGuids.contains(f.getObjectId()))
                {
                    jsonFiles.add(f);
                    jsonGuids.add(f.getObjectId());
                }
            }

            List<Integer> trackIds = new TableSelector(DbSchema.get("sequenceanalysis").getTable("reference_library_tracks"), PageFlowUtil.set("rowid"), new SimpleFilter(FieldKey.fromString("library_id"), libraryId), null).getArrayList(Integer.class);
            getLogger().info("total tracks: " + trackIds.size());
            for (Integer trackId : trackIds)
            {
                JsonFile f = prepareFeatureTrack(c, u, trackId, "Reference Annotations", false);
                if (f != null && !jsonGuids.contains(f.getObjectId()))
                {
                    f.setCategory("Reference Annotations");
                    jsonFiles.add(f);
                    jsonGuids.add(f.getObjectId());
                }
            }
        }

        JSONArray refSeq = new JSONArray();
        JSONObject trackList = new JSONObject();
        trackList.put("formatVersion", 1);

        JSONObject tracks = new JSONObject();

        Collections.sort(jsonFiles, new Comparator<JsonFile>()
        {
            @Override
            public int compare(JsonFile o1, JsonFile o2)
            {
                int ret = o1.getCategory().compareTo(o2.getCategory());
                if (ret != 0)
                {
                    return ret;
                }

                return o1.getLabel().compareTo(o2.getLabel());
            }
        });

        int compressedRefs = 0;
        Set<Integer> referenceIds = new HashSet<>();

        getLogger().info("total JSON files: " + jsonFiles.size());
        for (JsonFile f : jsonFiles)
        {
            getLogger().info("processing JSON file: " + f.getObjectId());
            if (f.getSequenceId() != null)
            {
                getLogger().info("adding ref seq: " + f.getSequenceId());
                if (f.getRefSeqsFile() == null)
                {
                    prepareRefSeq(f.getContainerObj(), u, f.getSequenceId(), true);
                }

                if (f.getRefSeqsFile() == null)
                {
                    throw new IOException("There was an error preparing ref seq file for sequence: " + f.getSequenceId());
                }

                referenceIds.add(f.getSequenceId());
                JSONArray arr = readFileToJsonArray(f.getRefSeqsFile());
                for (int i = 0; i < arr.length(); i++)
                {
                    refSeq.put(arr.get(i));
                }

                //inspect for compression
                if (hasFilesWithExtension(new File(f.getBaseDir(), "seq"), "txtz"))
                {
                    getLogger().info("reference is compressed");
                    compressedRefs++;
                }
                else
                {
                    getLogger().info("reference is not compressed");
                }

                //also add sym link to raw json
                mergeSequenceDirs(seqDir, new File(f.getBaseDir(), "seq"));
            }
            else if (f.getTrackId() != null)
            {
                getLogger().info("adding track: " + f.getTrackId());

                //try to recreate if it does not exist
                if (f.getTrackListFile() == null)
                {
                    prepareFeatureTrack(f.getContainerObj(), u, f.getTrackId(), f.getCategory(), true);
                    if (f.getTrackListFile() == null)
                    {
                        getLogger().error("this track lacks a trackList.conf file.  this probably indicates a problem when this resource was originally processed.  you should try to re-process it." + (f.getTrackRootDir() == null ? "" : "  expected to find file in: " + f.getTrackRootDir()));
                        continue;
                    }
                }

                JSONObject json = readFileToJson(f.getTrackListFile());
                if (json.containsKey("tracks"))
                {
                    JSONArray existingTracks = trackList.containsKey("tracks") ? trackList.getJSONArray("tracks") : new JSONArray();
                    JSONArray arr = json.getJSONArray("tracks");
                    for (int i = 0; i < arr.length(); i++)
                    {
                        JSONObject o = arr.getJSONObject(i);
                        if (f.getExtraTrackConfig() != null)
                        {
                            o.putAll(f.getExtraTrackConfig());
                        }

                        if (f.getCategory() != null)
                        {
                            o.put("category", f.getCategory());
                        }

                        if (o.get("urlTemplate") != null)
                        {
                            getLogger().debug("updating urlTemplate");
                            getLogger().debug("old: " + o.getString("urlTemplate"));
                            o.put("urlTemplate", o.getString("urlTemplate").replaceAll("^tracks/track-" + f.getTrackId() + "/data/tracks", "tracks"));
                            getLogger().debug("new: " + o.getString("urlTemplate"));
                        }

                        existingTracks.put(o);
                    }

                    trackList.put("tracks", existingTracks);
                }

                //even through we're loading the raw data based on urlTemplate, make a symlink from this location into our DB so help generate-names.pl work properly
                File sourceFile = new File(f.getTrackRootDir(), "tracks/track-" + f.getTrackId());
                File targetFile = new File(outDir, "tracks/track-" + f.getTrackId());

                createSymlink(targetFile, sourceFile);
            }
            else if (f.getOutputFile() != null)
            {
                getLogger().info("processing output file: " + f.getOutputFile());

                TableInfo ti = QueryService.get().getUserSchema(u, c, JBrowseManager.SEQUENCE_ANALYSIS).getTable("outputfiles");
                Set<FieldKey> keys = PageFlowUtil.set(
                        FieldKey.fromString("description"),
                        FieldKey.fromString("analysis_id"),
                        FieldKey.fromString("analysis_id/name"),
                        FieldKey.fromString("readset"),
                        FieldKey.fromString("readset/name"),
                        FieldKey.fromString("readset/subjectid"),
                        FieldKey.fromString("readset/sampletype"),
                        FieldKey.fromString("readset/platform"),
                        FieldKey.fromString("readset/application")
                );

                Map<FieldKey, ColumnInfo> cols = QueryService.get().getColumns(ti, keys);
                TableSelector outputFileTs = new TableSelector(ti, cols.values(), new SimpleFilter(FieldKey.fromString("rowid"), f.getOutputFile()), null);
                if (!outputFileTs.exists())
                {
                    _log.error("unable to find outputfile: " + f.getOutputFile());
                    continue;
                }

                ExpData d = f.getExpData();
                if (d == null || !d.getFile().exists())
                {
                    getLogger().error("unable to find file for output: " + f.getOutputFile());
                    continue;
                }

                File outputDir = new File(getTracksDir(f.getContainerObj()), "data-" + f.getOutputFile().toString());
                if (outputDir.exists())
                {
                    FileUtils.deleteDirectory(outputDir);
                }
                outputDir.mkdirs();

                Map<String, Object> metadata = new HashMap<>();
                if (outputFileTs.exists())
                {
                    try (Results results = outputFileTs.getResults())
                    {
                        results.next();
                        metadata.put("Description", results.getString(FieldKey.fromString("description")));
                        metadata.put("Readset", results.getString(FieldKey.fromString("readset/name")));
                        metadata.put("Subject Id", results.getString(FieldKey.fromString("readset/subjectid")));
                        metadata.put("Sample Type", results.getString(FieldKey.fromString("readset/sampletype")));
                        metadata.put("Platform", results.getString(FieldKey.fromString("readset/platform")));
                        metadata.put("Application", results.getString(FieldKey.fromString("readset/application")));
                    }
                    catch(SQLException e)
                    {
                        throw new IOException(e);
                    }
                }

                List<JSONObject> objList = processFile(d, outputDir, "data-" + f.getOutputFile(), f.getLabel(), metadata, f.getCategory(), f.getRefLibraryData());
                if (objList != null && !objList.isEmpty())
                {
                    for (JSONObject o : objList)
                    {
                        JSONArray existingTracks = trackList.containsKey("tracks") ? trackList.getJSONArray("tracks") : new JSONArray();
                        if (f.getExtraTrackConfig() != null)
                        {
                            o.putAll(f.getExtraTrackConfig());
                        }

                        if (f.getCategory() != null)
                        {
                            o.put("category", f.getCategory());
                        }

                        String outDirPrefix = "tracks/data-" + f.getOutputFile().toString();
                        if (o.get("urlTemplate") != null)
                        {
                            getLogger().debug("updating urlTemplate");
                            getLogger().debug("old: " + o.getString("urlTemplate"));
                            o.put("urlTemplate", o.getString("urlTemplate").replaceAll("^data/data-" + f.getOutputFile(), outDirPrefix));
                            getLogger().debug("new: " + o.getString("urlTemplate"));
                        }

                        existingTracks.put(o);
                        trackList.put("tracks", existingTracks);

                        File sourceFile = new File(getTracksDir(f.getContainerObj()), "data-" + f.getOutputFile().toString());
                        File targetFile = new File(outDir, outDirPrefix);
                        if (!targetFile.getParentFile().exists())
                        {
                            targetFile.getParentFile().mkdirs();
                        }

                        createSymlink(targetFile, sourceFile);
                    }
                }
            }
            else
            {
                getLogger().error("json file lacks refSeq and trackList, cannot be included.  this might indicate it is an unsupported file type, or that the file should be re-processed");
            }
        }

        if (referenceIds.size() != compressedRefs && compressedRefs > 0)
        {
            getLogger().error("Some references are compressed and some are not.  Total ref: " + referenceIds.size() + ", compressed: " + compressedRefs + ".  This will cause rendering problems.");
        }

        //add single track for reference sequence
        //combine ref seqs into a single track
        JSONArray existingTracks = trackList.containsKey("tracks") ? trackList.getJSONArray("tracks") : new JSONArray();
        JSONObject o = new JSONObject();
        o.put("category", "Reference Annotations");
        o.put("storeClass", "JBrowse/Store/Sequence/StaticChunked");
        o.put("chunkSize", 20000);
        o.put("label", "Reference sequence");
        o.put("key", "Reference sequence");
        o.put("type", "SequenceTrack");
        o.put("showTranslation", false);

        //NOTE: this isnt perfect.  these tracks might have been created in the past with a different setting, and in fact some might be compressed and some not.
        if (compressedRefs > 0 && compressedRefs == referenceIds.size())
        {
            o.put("compress", 1);
        }
        o.put("urlTemplate", "seq/{refseq_dirpath}/{refseq}-");
        existingTracks.put(o);
        trackList.put("tracks", existingTracks);

        //look in reference_aa_sequences and ref_nt_features and create track of these if they exist
        JSONObject codingRegionTrack = createCodingRegionTrack(c, referenceIds, trackDir);
        if (codingRegionTrack != null)
        {
            JSONArray existingTracks2 = trackList.containsKey("tracks") ? trackList.getJSONArray("tracks") : new JSONArray();
            existingTracks2.put(codingRegionTrack);
            trackList.put("tracks", existingTracks2);
        }


        JSONObject featureTrack = createFeatureTrack(c, referenceIds, trackDir);
        if (featureTrack != null)
        {
            JSONArray existingTracks2 = trackList.containsKey("tracks") ? trackList.getJSONArray("tracks") : new JSONArray();
            existingTracks2.put(featureTrack);
            trackList.put("tracks", existingTracks2);
        }

        writeJsonToFile(new File(seqDir, "refSeqs.json"), refSeq.toString(1));
        writeJsonToFile(new File(outDir, "trackList.json"), trackList.toString(1));
        writeJsonToFile(new File(outDir, "tracks.json"), tracks.toString(1));
        writeJsonToFile(new File(outDir, "tracks.conf"), "");

        File namesDir = new File(outDir, "names");
        if (!namesDir.exists())
            namesDir.mkdirs();

        List<String> args = new ArrayList<>(Arrays.asList("--out", outDir.getPath()));
        if (JBrowseManager.get().compressJSON())
        {
            args.add("--compress");
        }

        args.add("--verbose");
        args.add("--mem");
        args.add("512000000");

        args.add("--completionLimit");
        args.add("100");

        runScript("generate-names.pl", args);
    }

    private JSONObject createCodingRegionTrack(Container c, Set<Integer> referenceIds, File databaseTrackDir) throws IOException
    {
        //first add coding regions
        TableSelector ts = new TableSelector(DbSchema.get(JBrowseManager.SEQUENCE_ANALYSIS).getTable("ref_aa_sequences"), new SimpleFilter(FieldKey.fromString("ref_nt_id"), referenceIds, CompareType.IN), new Sort("ref_nt_id,start_location"));
        if (!ts.exists())
        {
            return null;
        }

        File aaFeaturesOutFile = new File(databaseTrackDir, "aaFeatures.gff");
        try (final BufferedWriter writer = new BufferedWriter(new FileWriter(aaFeaturesOutFile)))
        {
            //first find ref_aa_sequences
            ts.forEach(new Selector.ForEachBlock<ResultSet>()
            {
                @Override
                public void exec(ResultSet rs) throws SQLException
                {
                    String name = rs.getString("name");
                    Integer refNtId = rs.getInt("ref_nt_id");
                    Boolean isComplement = rs.getObject("isComplement") != null && rs.getBoolean("isComplement");
                    String exons = StringUtils.trimToNull(rs.getString("exons"));
                    if (exons == null)
                    {
                        return;
                    }

                    RefNtSequenceModel ref = RefNtSequenceModel.getForRowId(refNtId);
                    String refName = ref.getName();

                    try
                    {
                        String[] tokens = StringUtils.split(exons, ";");

                        String featureId = refName + "_" + name;
                        String strand = isComplement ? "-" : "+";
                        String[] lastExon = StringUtils.split(tokens[tokens.length - 1], "-");
                        if (lastExon.length != 2)
                        {
                            return;
                        }

                        writer.write(StringUtils.join(new String[]{refName, "ReferenceAA", "gene", rs.getString("start_location"), lastExon[1], ".", strand, ".", "ID=" + featureId + ";Note="}, '\t') + System.getProperty("line.separator"));

                        for (String exon : tokens)
                        {
                            String[] borders = StringUtils.split(exon, "-");
                            if (borders.length != 2)
                            {
                                getLogger().error("improper exon: " + exon);
                                return;
                            }

                            writer.write(StringUtils.join(new String[]{refName, "ReferenceAA", "CDS", borders[0], borders[1], ".", strand, ".", "Parent=" + featureId}, '\t') + System.getProperty("line.separator"));
                        }
                    }
                    catch (IOException e)
                    {
                        throw new SQLException(e);
                    }
                }
            });
        }

        //now process track
        String featureName = "CodingRegions";
        File outDir = new File(databaseTrackDir, "tmpTrackDir");
        if (outDir.exists())
        {
            FileUtils.deleteDirectory(outDir);
        }

        outDir.mkdirs();

        JSONObject ret = processFlatFile(c, aaFeaturesOutFile, outDir, "--gff", featureName, "Coding Regions", null, "Reference Annotations");

        //move file, so name parsing works properly
        File source = new File(databaseTrackDir, "tmpTrackDir/data/tracks/" + featureName);
        File dest = new File(databaseTrackDir, featureName);
        FileUtils.moveDirectory(source, dest);
        FileUtils.deleteDirectory(outDir);

        //update urlTemplate
        String relPath = FileUtil.relativePath(getBaseDir(c).getPath(), databaseTrackDir.getPath());
        getLogger().debug("using relative path: " + relPath);
        ret.put("urlTemplate", "tracks/" + featureName + "/{refseq}/trackData.json");

        aaFeaturesOutFile.delete();

        return ret;
    }

    private JSONObject createFeatureTrack(Container c, Set<Integer> referenceIds, File databaseTrackDir) throws IOException
    {
        //first add coding regions
        TableSelector ts = new TableSelector(DbSchema.get(JBrowseManager.SEQUENCE_ANALYSIS).getTable("ref_nt_features"), new SimpleFilter(FieldKey.fromString("ref_nt_id"), referenceIds, CompareType.IN), new Sort("ref_nt_id,nt_start"));
        if (!ts.exists())
        {
            return null;
        }

        File aaFeaturesOutFile = new File(databaseTrackDir, "ntFeatures.gff");
        try (final BufferedWriter writer = new BufferedWriter(new FileWriter(aaFeaturesOutFile)))
        {
            //first find ref_aa_sequences
            ts.forEach(new Selector.ForEachBlock<ResultSet>()
            {
                @Override
                public void exec(ResultSet rs) throws SQLException
                {
                    String name = rs.getString("name");
                    Integer refNtId = rs.getInt("ref_nt_id");
                    RefNtSequenceModel ref = RefNtSequenceModel.getForRowId(refNtId);
                    String refName = ref.getName();

                    try
                    {
                        String featureId = refName + "_" + name;
                        writer.write(StringUtils.join(new String[]{refName, "ReferenceNTFeatures", rs.getString("category"), rs.getString("nt_start"), rs.getString("nt_stop"), ".", "+", ".", "ID=" + featureId + ";Note="}, '\t') + System.getProperty("line.separator"));
                    }
                    catch (IOException e)
                    {
                        throw new SQLException(e);
                    }
                }
            });
        }

        //now process track
        String featureName = "SequenceFeatures";
        File outDir = new File(databaseTrackDir, "tmpTrackDir");
        if (outDir.exists())
        {
            FileUtils.deleteDirectory(outDir);
        }

        outDir.mkdirs();

        JSONObject ret = processFlatFile(c, aaFeaturesOutFile, outDir, "--gff", featureName, "Coding Regions", null, "Reference Annotations");

        //move file, so name parsing works properly
        File source = new File(databaseTrackDir, "tmpTrackDir/data/tracks/" + featureName);
        File dest = new File(databaseTrackDir, featureName);
        FileUtils.moveDirectory(source, dest);
        FileUtils.deleteDirectory(outDir);

        //update urlTemplate
        String relPath = FileUtil.relativePath(getBaseDir(c).getPath(), databaseTrackDir.getPath());
        getLogger().debug("using relative path: " + relPath);
        ret.put("urlTemplate", featureName + "/{refseq}/trackData.json");

        aaFeaturesOutFile.delete();

        return ret;
    }

    private boolean hasFilesWithExtension(File root, String ext)
    {
        for (File f : root.listFiles())
        {
            if (f.isDirectory())
            {
                if (hasFilesWithExtension(f, ext))
                {
                    return true;
                }
            }
            else if (ext.equals(FileUtil.getExtension(f)))
            {
                return true;
            }
        }

        return false;
    }

    private void mergeSequenceDirs(File targetDir, File sourceDir)
    {
        for (File f : sourceDir.listFiles())
        {
            if (f.isDirectory())
            {
                processDir(f, targetDir, f.getName(), 1);
            }
        }
    }

    private void createSymlink(File targetFile, File sourceFile) throws IOException
    {
        getLogger().info("creating sym link");
        getLogger().debug("source: " + sourceFile.getPath());
        getLogger().debug("target: " + targetFile.getPath());

        if (!sourceFile.exists())
        {
            getLogger().error("unable to find file: " + sourceFile.getPath());
        }

        if (targetFile.exists())
        {
            getLogger().warn("target of symlink already exists: " + targetFile.getPath());
            if (FileUtils.isSymlink(targetFile))
            {
                targetFile.delete();
            }
        }

        if (!targetFile.exists())
        {
            Files.createSymbolicLink(targetFile.toPath(), sourceFile.toPath());
        }
        else
        {
            getLogger().info("symlink target already exists, skipping: " + targetFile.getPath());
        }
    }

    private void processDir(File sourceDir, File targetDir, String relPath, int depth)
    {
        File newFile = new File(targetDir, relPath);
        if (!newFile.exists())
        {
            newFile.mkdirs();
        }

        for (File f : sourceDir.listFiles())
        {
            String ext = FileUtil.getExtension(f);
            if (f.isDirectory() && depth < 2)
            {
                processDir(f, targetDir, relPath + "/" + f.getName(), (depth + 1));
            }
            else if (depth >= 2 || "txtz".equals(ext) || "txt".equals(ext) || "jsonz".equals(ext) || "json".equals(ext) || ".htaccess".equals(f.getName()))
            {
                try
                {
                    File sourceFile = f;
                    File targetFile = new File(targetDir, relPath + "/" + f.getName());

                    createSymlink(targetFile, sourceFile);
                }
                catch (UnsupportedOperationException | IOException e)
                {
                    getLogger().error(e);
                }
            }
            else
            {
                getLogger().info("will not process file: " + f.getName());
            }
        }
    }

    public JsonFile prepareFeatureTrack(Container c, User u, Integer trackId, @Nullable String category, boolean forceRecreateJson) throws IOException
    {
        //validate track exists
        TableInfo ti = QueryService.get().getUserSchema(u, c, JBrowseManager.SEQUENCE_ANALYSIS).getTable("reference_library_tracks");
        TableSelector ts = new TableSelector(ti, new SimpleFilter(FieldKey.fromString("rowid"), trackId), null);
        Map<String, Object> trackRowMap = ts.getMap();

        if (trackRowMap.get("fileid") == null)
        {
            throw new IllegalArgumentException("Track does not have a file: " + trackId);
        }

        ExpData data = ExperimentService.get().getExpData((int)trackRowMap.get("fileid"));
        if (!data.getFile().exists())
        {
            throw new IllegalArgumentException("File does not exist: " + data.getFile().getPath());
        }

        //find existing resource
        TableInfo jsonFiles = DbSchema.get(JBrowseSchema.NAME).getTable(JBrowseSchema.TABLE_JSONFILES);
        TableSelector ts1 = new TableSelector(jsonFiles, new SimpleFilter(FieldKey.fromString("trackid"), trackId), null);
        JsonFile jsonFile = null;
        if (ts1.exists())
        {
            jsonFile = ts1.getObject(JsonFile.class);
            if (!forceRecreateJson)
            {
                return jsonFile;
            }
        }

        File outDir = new File(getTracksDir(data.getContainer()), "track-" + trackId.toString());
        if (outDir.exists())
        {
            FileUtils.deleteDirectory(outDir);
        }
        outDir.mkdirs();

        //else create
        if (jsonFile == null)
        {
            Map<String, Object> jsonRecord = new CaseInsensitiveHashMap();
            jsonRecord.put("trackid", trackId);
            jsonRecord.put("relPath", FileUtil.relativePath(getBaseDir(data.getContainer()).getPath(), outDir.getPath()));
            jsonRecord.put("container", data.getContainer().getId());
            jsonRecord.put("created", new Date());
            jsonRecord.put("createdby", u.getUserId());
            jsonRecord.put("modified", new Date());
            jsonRecord.put("modifiedby", u.getUserId());
            jsonRecord.put("objectid", new GUID().toString().toUpperCase());

            TableInfo jsonTable = DbSchema.get(JBrowseSchema.NAME).getTable(JBrowseSchema.TABLE_JSONFILES);
            Table.insert(u, jsonTable, jsonRecord);

            jsonFile = ts1.getObject(JsonFile.class);
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("Description", trackRowMap.get("description"));

        processFile(data, outDir, "track-" + (trackRowMap.get("rowid")).toString(), (String)trackRowMap.get("name"), metadata, category == null ? (String)trackRowMap.get("category") : category, null);

        return jsonFile;
    }

    private List<JSONObject> processFile(ExpData data, File outDir, String featureName, String featureLabel, Map<String, Object> metadata, String category, @Nullable ExpData refGenomeData) throws IOException
    {
        FileType bamType = new FileType("bam", FileType.gzSupportLevel.NO_GZ);
        FileType vcfType = new FileType("vcf", FileType.gzSupportLevel.SUPPORT_GZ);
        File input = data.getFile();

        String ext = FileUtil.getExtension(data.getFile());
        if ("gff3".equalsIgnoreCase(ext) || "gff".equalsIgnoreCase(ext) || "gtf".equalsIgnoreCase(ext))
        {
            JSONObject ret = processFlatFile(data.getContainer(), data.getFile(), outDir, "--gff", featureName, featureLabel, metadata, category);
            return ret == null ? null : Arrays.asList(ret);
        }
        else if ("bed".equalsIgnoreCase(ext) || "bedgraph".equalsIgnoreCase(ext))
        {
            JSONObject ret = processFlatFile(data.getContainer(), data.getFile(), outDir, "--bed", featureName, featureLabel, metadata, category);
            return ret == null ? null : Arrays.asList(ret);
        }
        else if ("gbk".equalsIgnoreCase(ext))
        {
            JSONObject ret = processFlatFile(data.getContainer(), data.getFile(), outDir, "--gbk", featureName, featureLabel, metadata, category);
            return ret == null ? null : Arrays.asList(ret);
        }
        else if ("bigwig".equalsIgnoreCase(ext) || "bw".equalsIgnoreCase(ext))
        {
            JSONObject ret = processBigWig(data, outDir, featureName, featureLabel, metadata, category);
            return ret == null ? null : Arrays.asList(ret);
        }
        else if (bamType.isType(input))
        {
            List<JSONObject> ret = processBam(data, outDir, featureName, featureLabel, metadata, category);

            return ret.isEmpty() ? null : ret;
        }
        else if (vcfType.isType(input))
        {
            JSONObject ret = processVCF(data, outDir, featureName, featureLabel, metadata, category, refGenomeData);
            return ret == null ? null : Arrays.asList(ret);
        }
        else
        {
            getLogger().error("Unknown extension, skipping: " + ext);
            return null;
        }
    }

    private List<JSONObject> processBam(ExpData data, File outDir, String featureName, String featureLabel, Map<String, Object> metadata, String category) throws IOException
    {
        getLogger().info("processing BAM file");
        List<JSONObject> ret = new ArrayList<>();

        if (outDir.exists())
        {
            outDir.delete();
        }
        outDir.mkdirs();

        File targetFile = new File(outDir, data.getFile().getName());

        //check for BAI
        File indexFile = new File(data.getFile().getPath() + ".bai");
        if (!indexFile.exists())
        {
            getLogger().info("unable to find index file for BAM, creating");
            try (SAMFileReader reader = new SAMFileReader(data.getFile()))
            {
                reader.setValidationStringency(ValidationStringency.SILENT);

                BAMIndexer.createIndex(reader, indexFile);
            }
        }

        //make sym link
        createSymlink(targetFile, data.getFile());
        createSymlink(new File(targetFile.getPath() + ".bai"), indexFile);

        //create track JSON
        JSONObject o = new JSONObject();
        o.put("category", "Alignments");
        o.put("storeClass", "JBrowse/Store/SeqFeature/BAM");
        o.put("label", featureName);
        o.put("type", "JBrowse/View/Track/Alignments2");
        o.put("key", featureLabel);

        String relPath = FileUtil.relativePath(getBaseDir(data.getContainer()).getPath(), outDir.getPath());
        getLogger().debug("using relative path: " + relPath);
        o.put("urlTemplate", relPath + "/" + targetFile.getName());

        if (metadata != null)
            o.put("metadata", metadata);

        if (category != null)
            o.put("category", category);

        ret.add(o);

        //add coverage track
        JSONObject coverage = new JSONObject();
        coverage.putAll(o);
        coverage.put("label", featureName + "_coverage");
        coverage.put("key", featureLabel + " Coverage");
        coverage.put("type", "JBrowse/View/Track/SNPCoverage");
        ret.add(coverage);

        return ret;
    }

    private JSONObject processVCF(ExpData data, File outDir, String featureName, String featureLabel, Map<String, Object> metadata, String category, @Nullable ExpData refGenomeData) throws IOException
    {
        getLogger().info("processing VCF file: " + data.getFile().getName());
        FileType vcfType = new FileType("vcf", FileType.gzSupportLevel.SUPPORT_GZ);
        FileType bcfType = new FileType("bcf", FileType.gzSupportLevel.SUPPORT_GZ);

        if (outDir.exists())
        {
            outDir.delete();
        }
        outDir.mkdirs();

        File inputFile = data.getFile();

        if (vcfType.isType(inputFile))
        {
            //if file is not bgziped, do so
            if (!"gz".equals(FileUtil.getExtension(inputFile)))
            {
                File compressed = new File(inputFile.getPath() + ".gz");
                if (!compressed.exists())
                {
                getLogger().info("bgzipping VCF file");
                bgzip(inputFile, compressed);
                }
                else
                {
                    getLogger().info("there is already a bgzipped file present, no need to repeat");
                }

                inputFile = compressed;
            }
        }
        else if (bcfType.isType(inputFile))
        {
            getLogger().info("file is already BCF, no action needed");
        }

        //check for index.  should be made on compressed file
        File indexFile = new File(inputFile.getPath() + ".tbi");
        if (!indexFile.exists())
        {
            getLogger().info("unable to find index file for VCF, creating");
            try
            {
                indexFile = SequenceAnalysisService.get().createTabixIndex(inputFile, getLogger());
            }
            catch (PipelineJobException e)
            {
                getLogger().error("Unable to run tabix to create VCF index.  you will need to do this manually in order to view this file in JBrowse", e);
            }

            //TabixIndex idx = IndexFactory.createTabixIndex(inputFile, new VCFCodec(), TabixFormat.VCF, null);
            //idx.write(indexFile);
        }

        File targetFile = new File(outDir, inputFile.getName());

        //make sym link
        createSymlink(targetFile, inputFile);
        File targetIndex = new File(targetFile.getPath() + ".tbi");
        createSymlink(targetIndex, indexFile);

        //create track JSON
        JSONObject o = new JSONObject();
        o.put("category", "Variants");
        o.put("storeClass", "JBrowse/Store/SeqFeature/VCFTabix");
        o.put("label", featureName);
        o.put("type", "JBrowse/View/Track/HTMLVariants");
        o.put("key", featureLabel);

        String relPath = FileUtil.relativePath(getBaseDir(data.getContainer()).getPath(), outDir.getPath());
        getLogger().debug("using relative path: " + relPath);
        o.put("urlTemplate", relPath + "/" + targetFile.getName());

        if (metadata != null)
            o.put("metadata", metadata);

        if (category != null)
            o.put("category", category);

        return o;
    }

    private JSONObject processBigWig(ExpData data, File outDir, String featureName, String featureLabel, Map<String, Object> metadata, String category) throws IOException
    {
        if (outDir.exists())
        {
            outDir.delete();
        }
        outDir.mkdirs();

        File targetFile = new File(outDir, data.getFile().getName());
        createSymlink(targetFile, data.getFile());

        //create track JSON
        JSONObject o = new JSONObject();
        o.put("category", "BigWig");
        o.put("storeClass", "JBrowse/Store/SeqFeature/BigWig");
        o.put("label", featureName);
        o.put("type", "JBrowse/View/Track/Wiggle/XYPlot");
        o.put("key", featureLabel);
        String relPath = FileUtil.relativePath(getBaseDir(data.getContainer()).getPath(), outDir.getPath());
        getLogger().debug("using relative path: " + relPath);
        o.put("urlTemplate", relPath + "/" + targetFile.getName());

        if (metadata != null)
            o.put("metadata", metadata);

        if (category != null)
            o.put("category", category);

        return o;
    }

    private JSONObject processFlatFile(Container c, File inputFile, File outDir, String typeArg, String featureName, String featureLabel, Map<String, Object> metadata, String category) throws IOException
    {
        List<String> args = new ArrayList<>();

        args.add(typeArg);
        args.add(inputFile.getPath());

        //NOTE: this oddity is a quirk of jbrowse.  label is the background name.  'key' is the user-facing label.
        args.add("--trackLabel");
        args.add(featureName);

        args.add("--key");
        args.add(featureLabel);

        //TODO
        //args.add("--trackType");
        //args.add("--className");
        //args.add("--type");

        if (JBrowseManager.get().compressJSON())
        {
            args.add("--compress");
        }

        //to avoid issues w/ perl and escaping characters, just set the working directory to the output folder
        //args.add("--out");
        //args.add(outDir.getPath());

        runScript("flatfile-to-json.pl", args, outDir);

        File trackList = new File(outDir, "data/trackList.json");
        if (trackList.exists())
        {
            JSONObject obj = readFileToJson(trackList);
            String urlTemplate = obj.getJSONArray("tracks").getJSONObject(0).getString("urlTemplate");
            String relPath = FileUtil.relativePath(getBaseDir(c).getPath(), new File(outDir, "data").getPath());
            getLogger().debug("using relative path: " + relPath);
            getLogger().debug("original urlTemplate: " + urlTemplate);
            urlTemplate = relPath + "/" + urlTemplate;
            getLogger().debug("new urlTemplate: " + urlTemplate);
            obj.getJSONArray("tracks").getJSONObject(0).put("urlTemplate", urlTemplate);

            JSONObject metadataObj = obj.getJSONArray("tracks").getJSONObject(0).containsKey("metadata") ? obj.getJSONArray("tracks").getJSONObject(0).getJSONObject("metadata") : new JSONObject();
            if (metadata != null)
                metadataObj.putAll(metadata);

            obj.getJSONArray("tracks").getJSONObject(0).put("metadata", metadataObj);

            if (category != null)
                obj.getJSONArray("tracks").getJSONObject(0).put("category", category);

            writeJsonToFile(trackList, obj.toString(1));

            return obj.getJSONArray("tracks").getJSONObject(0);
        }
        else
        {
            getLogger().info("track list file does not exist, expected: " + trackList.getPath());
        }

        return null;
    }

    private String readFile(File file) throws IOException
    {
        try (BufferedReader reader = new BufferedReader(new FileReader(file)))
        {
            String line;
            StringBuilder stringBuilder = new StringBuilder();
            String ls = System.getProperty("line.separator");

            while ((line = reader.readLine()) != null)
            {
                stringBuilder.append(line);
                stringBuilder.append(ls);
            }

            return stringBuilder.toString();
        }
    }

    private JSONObject readFileToJson(File file) throws IOException
    {
        String contents = readFile(file);

        return new JSONObject(contents);
    }

    private JSONArray readFileToJsonArray(File file) throws IOException
    {
        String contents = readFile(file);

        return new JSONArray(contents);
    }

    private void writeJsonToFile(File output, String json) throws IOException
    {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(output)))
        {
            writer.write(json.equals("{}") ? "" : json);
        }
    }

//    private File convertToBCF(File input, @Nullable ExpData refGenomeData)
//    {
//        try (VCFFileReader reader = new VCFFileReader(input, false))
//        {
//            VCFHeader header = new VCFHeader(reader.getFileHeader());
//            SAMSequenceDictionary sequenceDictionary = header.getSequenceDictionary();
//
//            //we need contig lines to convert to BCF.  if these are present we can use BCF.  otherwise just bgzip
//            if (header.getContigLines().isEmpty())
//            {
//                //attempt to create header
//                if (refGenomeData != null && refGenomeData.getFile().exists())
//                {
//                    sequenceDictionary = SequenceAnalysisService.get().makeSequenceDictionary(refGenomeData.getFile());
//                    header.setSequenceDictionary(sequenceDictionary);
//                }
//
//                if (sequenceDictionary == null)
//                {
//                    //cant use BCF, just bgzip
//                    File output = new File(input.getPath() + ".bgz");
//                    return bgzip(input, output);
//                }
//            }
//
//            VariantContextWriterBuilder builder = new VariantContextWriterBuilder();
//            File output = new File(input.getParentFile(), FileUtil.getBaseName(input) + ".bcf");
//            builder.setOutputFile(output);
//            if (sequenceDictionary != null)
//            {
//                builder.setReferenceDictionary(sequenceDictionary);
//                //builder.setOption(Options.INDEX_ON_THE_FLY);
//            }
//
//            builder.unsetOption(Options.INDEX_ON_THE_FLY);
//            builder.setOption(Options.ALLOW_MISSING_FIELDS_IN_HEADER);
//
//            try (VariantContextWriter writer = builder.build();CloseableIterator<VariantContext> iterator = reader.iterator())
//            {
//                writer.writeHeader(header);
//                while (iterator.hasNext())
//                {
//                    VariantContext context = iterator.next();
//                    writer.add(context);
//                }
//
//                CloserUtil.close(iterator);
//                CloserUtil.close(reader);
//                writer.close();
//            }
//
//            return output;
//        }
//    }

    private File bgzip(File input, File output)
    {
        try (FileInputStream i = new FileInputStream(input); BlockCompressedOutputStream o = new BlockCompressedOutputStream(new FileOutputStream(output), output))
        {
            FileUtil.copyData(i, o);

            return output;
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }
}