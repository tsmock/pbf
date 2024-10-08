// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.pbf.io;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.data.DataSource;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.tools.Logging;

import crosby.binary.BinarySerializer;
import crosby.binary.Osmformat;
import crosby.binary.Osmformat.DenseInfo;
import crosby.binary.Osmformat.Relation.MemberType;
import crosby.binary.StringTable;
import crosby.binary.file.BlockOutputStream;
import crosby.binary.file.FileBlock;

/**
 * OSM writer for the PBF file format.
 * @author Don-vip
 */
public class PbfWriter implements Closeable {

    private final PbfSerializer out;

    /**
     * Constructs a new {@code PbfWriter}.
     * @param out output stream
     */
    public PbfWriter(OutputStream out) {
        this.out = new PbfSerializer(new BlockOutputStream(out));
    }

    // Copied from OsmosisSerializer (public domain)
    protected static class PbfSerializer extends BinarySerializer {

        /** Additional configuration flag for whether to serialize into DenseNodes/DenseInfo? */
        protected boolean useDense = true;

        /** Has the header been written yet? */
        protected boolean headerWritten;

        /**
         * Constructs a new {@code PbfSerializer}.
         *
         * @param output The PBF block stream to send serialized data
         */
        public PbfSerializer(BlockOutputStream output) {
            super(output);
        }

        /**
         * Change the flag of whether to use the dense format.
         *
         * @param useDense The new use dense value.
         */
        public void setUseDense(boolean useDense) {
            this.useDense = useDense;
        }

        /**
         * Base class containing common code needed for serializing each type of primitives.
         */
        private abstract class Prim<T extends OsmPrimitive> {
            /** Queue that tracks the list of all primitives. */
            ArrayList<T> contents = new ArrayList<>();

            /**
             * Add to the queue.
             *
             * @param item The entity to add
             */
            public void add(T item) {
                contents.add(item);
            }

            /**
             * Add all of the tags of all entities in the queue to the stringtable.
             */
            public void addStringsToStringtable() {
                StringTable stable = getStringTable();
                for (T i : contents) {
                    for (Map.Entry<String, String> tag : i.getKeys().entrySet()) {
                        stable.incr(tag.getKey());
                        stable.incr(tag.getValue());
                    }
                    if (!omit_metadata) {
                        stable.incr(getUserId(i));
                    }
                }
            }

            private String getUserId(OsmPrimitive osm) {
                String userId = osm.getUser() != null ? osm.getUser().getName() : null;
                if (userId == null)
                    userId = "";
                return userId;
            }

            public void serializeMetadataDense(DenseInfo.Builder b, List<? extends OsmPrimitive> entities) {
                if (omit_metadata) {
                    return;
                }

                long lasttimestamp = 0;
                long lastchangeset = 0;
                int lastuserSid = 0;
                int lastuid = 0;
                StringTable stable = getStringTable();
                for (OsmPrimitive e : entities) {

                    int uid = e.getUser() == null ? -1 : (int) e.getUser().getId();
                    int userSid = stable.getIndex(getUserId(e));
                    int timestamp = (int) (e.getInstant().toEpochMilli() / date_granularity);
                    int version = e.getVersion();
                    long changeset = e.getChangesetId();

                    b.addVersion(version);
                    b.addTimestamp(timestamp - lasttimestamp);
                    lasttimestamp = timestamp;
                    b.addChangeset(changeset - lastchangeset);
                    lastchangeset = changeset;
                    b.addUid(uid - lastuid);
                    lastuid = uid;
                    b.addUserSid(userSid - lastuserSid);
                    lastuserSid = userSid;
                }
            }

            public Osmformat.Info.Builder serializeMetadata(OsmPrimitive e) {
                StringTable stable = getStringTable();
                Osmformat.Info.Builder b = Osmformat.Info.newBuilder();
                if (!omit_metadata) {
                    if (e.getUser() != null) {
                        b.setUid((int) e.getUser().getId());
                        b.setUserSid(stable.getIndex(e.getUser().getName()));
                    }
                    b.setTimestamp(e.getInstant().toEpochMilli() / date_granularity);
                    b.setVersion(e.getVersion());
                    b.setChangeset(e.getChangesetId());
                }
                return b;
            }
        }

        private class NodeGroup extends Prim<Node> implements PrimGroupWriterInterface {

            @Override
            public Osmformat.PrimitiveGroup serialize() {
                if (useDense) {
                    return serializeDense();
                } else {
                    return serializeNonDense();
                }
            }

            /**
             * Serialize all nodes in the 'dense' format.
             * @return a group of primitives
             */
            public Osmformat.PrimitiveGroup serializeDense() {
                if (contents.isEmpty()) {
                    return null;
                }
                Osmformat.PrimitiveGroup.Builder builder = Osmformat.PrimitiveGroup.newBuilder();
                StringTable stable = getStringTable();

                long lastlat = 0;
                long lastlon = 0;
                long lastid = 0;
                Osmformat.DenseNodes.Builder bi = Osmformat.DenseNodes.newBuilder();
                boolean doesBlockHaveTags = false;
                // Does anything in this block have tags?
                for (Node i : contents) {
                    doesBlockHaveTags = doesBlockHaveTags || i.hasKeys();
                }
                if (!omit_metadata) {
                    Osmformat.DenseInfo.Builder bdi = Osmformat.DenseInfo.newBuilder();
                    serializeMetadataDense(bdi, contents);
                    bi.setDenseinfo(bdi);
                }

                for (Node i : contents) {
                    long id = i.getUniqueId();
                    bi.addId(id - lastid);
                    lastid = id;
                    if (i.isLatLonKnown()) {
                        int lat = mapDegrees(i.lat());
                        int lon = mapDegrees(i.lon());
                        bi.addLon(lon - lastlon);
                        lastlon = lon;
                        bi.addLat(lat - lastlat);
                        lastlat = lat;
                    }

                    // Then we must include tag information.
                    if (doesBlockHaveTags) {
                        for (Map.Entry<String, String> t : i.getKeys().entrySet()) {
                            bi.addKeysVals(stable.getIndex(t.getKey()));
                            bi.addKeysVals(stable.getIndex(t.getValue()));
                        }
                        bi.addKeysVals(0); // Add delimiter.
                    }
                }
                builder.setDense(bi);
                return builder.build();
            }

            /**
             * Serialize all nodes in the non-dense format.
             * @return a group of primitives
             */
            public Osmformat.PrimitiveGroup serializeNonDense() {
                if (contents.isEmpty()) {
                    return null;
                }
                StringTable stable = getStringTable();
                Osmformat.PrimitiveGroup.Builder builder = Osmformat.PrimitiveGroup.newBuilder();
                for (Node i : contents) {
                    long id = i.getUniqueId();
                    int lat = mapDegrees(i.lat());
                    int lon = mapDegrees(i.lon());
                    Osmformat.Node.Builder bi = Osmformat.Node.newBuilder();
                    bi.setId(id);
                    bi.setLon(lon);
                    bi.setLat(lat);
                    for (Map.Entry<String, String> t : i.getKeys().entrySet()) {
                        bi.addKeys(stable.getIndex(t.getKey()));
                        bi.addVals(stable.getIndex(t.getValue()));
                    }
                    if (!omit_metadata) {
                        bi.setInfo(serializeMetadata(i));
                    }
                    builder.addNodes(bi);
                }
                return builder.build();
            }
        }

        private class WayGroup extends Prim<Way> implements
                PrimGroupWriterInterface {
            @Override
            public Osmformat.PrimitiveGroup serialize() {
                if (contents.isEmpty()) {
                    return null;
                }

                StringTable stable = getStringTable();
                Osmformat.PrimitiveGroup.Builder builder = Osmformat.PrimitiveGroup.newBuilder();
                for (Way i : contents) {
                    Osmformat.Way.Builder bi = Osmformat.Way.newBuilder();
                    bi.setId(i.getUniqueId());
                    long lastid = 0;
                    for (Node j : i.getNodes()) {
                        long id = j.getUniqueId();
                        bi.addRefs(id - lastid);
                        lastid = id;
                    }
                    for (Map.Entry<String, String> t : i.getKeys().entrySet()) {
                        bi.addKeys(stable.getIndex(t.getKey()));
                        bi.addVals(stable.getIndex(t.getValue()));
                    }
                    if (!omit_metadata) {
                        bi.setInfo(serializeMetadata(i));
                    }
                    builder.addWays(bi);
                }
                return builder.build();
            }
        }

        private class RelationGroup extends Prim<Relation> implements PrimGroupWriterInterface {
            @Override
            public void addStringsToStringtable() {
                StringTable stable = getStringTable();
                super.addStringsToStringtable();
                for (Relation i : contents) {
                    for (RelationMember j : i.getMembers()) {
                        stable.incr(j.getRole());
                    }
                }
            }

            @Override
            public Osmformat.PrimitiveGroup serialize() {
                if (contents.isEmpty()) {
                    return null;
                }

                StringTable stable = getStringTable();
                Osmformat.PrimitiveGroup.Builder builder = Osmformat.PrimitiveGroup.newBuilder();
                for (Relation i : contents) {
                    Osmformat.Relation.Builder bi = Osmformat.Relation.newBuilder();
                    bi.setId(i.getUniqueId());
                    RelationMember[] arr = new RelationMember[i.getMembers().size()];
                    i.getMembers().toArray(arr);
                    long lastid = 0;
                    for (RelationMember j : i.getMembers()) {
                        long id = j.getMember().getUniqueId();
                        bi.addMemids(id - lastid);
                        lastid = id;
                        if (j.getType() == OsmPrimitiveType.NODE) {
                            bi.addTypes(MemberType.NODE);
                        } else if (j.getType() == OsmPrimitiveType.WAY) {
                            bi.addTypes(MemberType.WAY);
                        } else if (j.getType() == OsmPrimitiveType.RELATION) {
                            bi.addTypes(MemberType.RELATION);
                        } else {
                            assert false; // Software bug: Unknown entity.
                        }
                        bi.addRolesSid(stable.getIndex(j.getRole()));
                    }

                    for (Map.Entry<String, String> t : i.getKeys().entrySet()) {
                        bi.addKeys(stable.getIndex(t.getKey()));
                        bi.addVals(stable.getIndex(t.getValue()));
                    }
                    if (!omit_metadata) {
                        bi.setInfo(serializeMetadata(i));
                    }
                    builder.addRelations(bi);
                }
                return builder.build();
            }
        }

        /* One list for each type */
        private WayGroup ways;
        private NodeGroup nodes;
        private RelationGroup relations;

        private Processor processor = new Processor();

        /**
         * Buffer up events into groups that are all of the same type, or all of the
         * same length, then process each buffer.
         */
        public class Processor {

            public void processSources(Collection<DataSource> sources) {
                switchTypes();
                // Can only write one bbox
                if (!sources.isEmpty()) {
                    processBounds(sources.iterator().next());
                }
            }

            /**
             * Check if we've reached the batch size limit and process the batch if we have.
             */
            public void checkLimit() {
                total_entities++;
                if (++batch_size < batch_limit) {
                    return;
                }
                switchTypes();
                processBatch();
            }

            /**
             * Process node.
             * @param node node
             */
            public void processNode(Node node) {
                if (nodes == null) {
                    writeEmptyHeaderIfNeeded();
                    switchTypes();
                    nodes = new NodeGroup();
                }
                if (node.isLatLonKnown()) {
                    nodes.add(node);
                    checkLimit();
                }
            }

            /**
             * Process way.
             * @param way way
             */
            public void processWay(Way way) {
                if (ways == null) {
                    writeEmptyHeaderIfNeeded();
                    switchTypes();
                    ways = new WayGroup();
                }
                ways.add(way);
                checkLimit();
            }

            /**
             * Process relation.
             * @param relation relation
             */
            public void processRelation(Relation relation) {
                if (relations == null) {
                    writeEmptyHeaderIfNeeded();
                    switchTypes();
                    relations = new RelationGroup();
                }
                relations.add(relation);
                checkLimit();
            }
        }

        /**
         * At the end of this function, all of the lists of unprocessed 'things'
         * must be null
         */
        private void switchTypes() {
            if (nodes != null) {
                groups.add(nodes);
                nodes = null;
            } else if (ways != null) {
                groups.add(ways);
                ways = null;
            } else if (relations != null) {
                groups.add(relations);
                relations = null;
            } else {
                // No data. Is this an empty file?
                Logging.debug("No PBF data. Is this an empty file?");
            }
        }

        public void processBounds(DataSource entity) {
            Osmformat.HeaderBlock.Builder headerblock = Osmformat.HeaderBlock.newBuilder();

            Osmformat.HeaderBBox.Builder bbox = Osmformat.HeaderBBox.newBuilder();
            bbox.setLeft(mapRawDegrees(entity.bounds.getMinLon()));
            bbox.setBottom(mapRawDegrees(entity.bounds.getMinLat()));
            bbox.setRight(mapRawDegrees(entity.bounds.getMaxLon()));
            bbox.setTop(mapRawDegrees(entity.bounds.getMaxLat()));
            headerblock.setBbox(bbox);

            if (entity.origin != null) {
                headerblock.setSource(entity.origin);
            }
            finishHeader(headerblock);
        }

        /** Write empty header block when there's no bounds entity. */
        public void writeEmptyHeaderIfNeeded() {
            if (headerWritten) {
                return;
            }
            Osmformat.HeaderBlock.Builder headerblock = Osmformat.HeaderBlock.newBuilder();
            finishHeader(headerblock);
        }

        /**
         * Write the header fields that are always needed.
         *
         * @param headerblock Incomplete builder to complete and write.
         * */
        public void finishHeader(Osmformat.HeaderBlock.Builder headerblock) {
            headerblock.setWritingprogram("JOSM");
            headerblock.addRequiredFeatures("OsmSchema-V0.6");
            if (useDense) {
                headerblock.addRequiredFeatures("DenseNodes");
            }
            Osmformat.HeaderBlock message = headerblock.build();
            try {
                output.write(FileBlock.newInstance("OSMHeader", message.toByteString(), null));
            } catch (IOException e) {
                throw new RuntimeException("Unable to write OSM header.", e);
            }
            headerWritten = true;
        }

        public void process(DataSet ds) {
            processor.processSources(ds.getDataSources());
            Comparator<OsmPrimitive> cmp = Comparator.comparingLong(OsmPrimitive::getUniqueId);
            ds.getNodes().stream().sorted(cmp).filter(Node::isUsable).forEach(processor::processNode);
            ds.getWays().stream().sorted(cmp).filter(Way::isUsable).forEach(processor::processWay);
            ds.getRelations().stream().sorted(cmp).filter(Relation::isUsable).forEach(processor::processRelation);
        }

        public void complete() {
            try {
                switchTypes();
                processBatch();
                flush();
            } catch (IOException e) {
                throw new RuntimeException("Unable to complete the PBF file.", e);
            }
        }
    }

    /**
     * Writes data from an OSM data layer.
     * @param layer data layer
     */
    public void writeLayer(OsmDataLayer layer) {
        writeData(layer.getDataSet());
    }

    /**
     * Writes data from a dataset.
     * @param ds dataset
     */
    public void writeData(DataSet ds) {
        out.process(ds);
        out.complete();
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
