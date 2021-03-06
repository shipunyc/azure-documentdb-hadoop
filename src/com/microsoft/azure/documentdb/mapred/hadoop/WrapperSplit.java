//------------------------------------------------------------
// Copyright (c) Microsoft Corporation.  All rights reserved.
//------------------------------------------------------------

package com.microsoft.azure.documentdb.mapred.hadoop;

import java.io.*;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.util.ReflectionUtils;

import com.microsoft.azure.documentdb.hadoop.DocumentDBInputSplit;

/**
 * A split in the old mapred.* API that represents a split from Documentdb.
 */
@SuppressWarnings("deprecation")
public class WrapperSplit extends FileSplit implements Writable {
    private DocumentDBInputSplit wrappedSplit;

    /**
     * A parameter-less constructor for deserialization.
     */
    public WrapperSplit() {
        super((Path) null, 0, 0, (String[]) null);
    }

    /**
     * Create a split to wrap a given documentdb split.
     * 
     * @param wrappedSplit
     *            The split to wrap.
     * @param file
     *            The file path for the partition in Hive (needs to match).
     * @param conf
     *            The configuration.
     */
    public WrapperSplit(DocumentDBInputSplit wrappedSplit, Path file, JobConf conf) {
        super(file, 0, 0, conf);
        this.wrappedSplit = wrappedSplit;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readFields(DataInput in) throws IOException {
        super.readFields(in);
        String className = in.readUTF();
        try {
            wrappedSplit = (DocumentDBInputSplit) ReflectionUtils.newInstance(Class.forName(className),
                    new Configuration());
        } catch (Exception e) {
            throw new IOException(e);
        }
        wrappedSplit.readFields(in);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(DataOutput out) throws IOException {
        super.write(out);
        out.writeUTF(wrappedSplit.getClass().getName());
        wrappedSplit.write(out);
    }

    /**
     * Returns the wrapped DocumentDBInputSplit instance.
     * @return DocumentDBInputSplit instance.
     */
    public DocumentDBInputSplit getWrappedSplit() {
        return wrappedSplit;
    }
}