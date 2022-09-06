package org.apache.mina.test.protocol;

public class ProtocolPacket {

    private int length;
    private byte flag;
    private String content;

    public ProtocolPacket(byte flag, String content) {
        this.flag = flag;
        this.content = content;
        int tmp = (content == null) ? 0 : content.getBytes().length;
        // length 表示整个数据包的长度，包括了 byte 类型的 flag 和 int 类型的 length 自身
        this.length = tmp + 4 + 1;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public byte getFlag() {
        return flag;
    }

    public void setFlag(byte flag) {
        this.flag = flag;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    @Override
    public String toString() {
        return "ProtocolPack{" +
                "length=" + length +
                ", flag=" + flag +
                ", content='" + content + '\'' +
                '}';
    }
}
