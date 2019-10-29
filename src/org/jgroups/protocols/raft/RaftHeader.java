package org.jgroups.protocols.raft;

import org.jgroups.Header;
import org.jgroups.util.Bits;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * @author Bela Ban
 * @since  0.1
 */
public abstract class RaftHeader extends Header {
    protected int term;

    public RaftHeader() {}
    public RaftHeader(int term) {this.term=term;}

    public int        term()      {return term;}
    public RaftHeader term(int t) {term=t; return this;}


    public int serializedSize() {
        return Bits.size(term);
    }

    public void writeTo(DataOutput out) throws IOException {
        Bits.writeInt(term, out);
    }

    public void readFrom(DataInput in) throws IOException, ClassNotFoundException {
        term=Bits.readInt(in);
    }

    public String toString() {return getClass().getSimpleName() + ": term=" + term;}
}
