/*
 * SquashFS support, extracted from the firmware toolset of AEmulator-Plus (GPL-3.0; see LICENSE):
 * the SquashFS 4 reader and the walk entry points of FirmwareToolset, without the OEM container
 * toolset around them. The 3.1 reader lives in Squash3 (same package). Only the zip-writing
 * helper of the original is left out; nothing here writes archives.
 */
package app.aemu.importer.tools;

import app.aemu.archive.ArcStreams;
import app.aemu.archive.NativeArc;
import com.github.luben.zstd.Zstd;
import org.anarres.lzo.LzoAlgorithm;
import org.anarres.lzo.LzoDecompressor;
import org.anarres.lzo.LzoLibrary;
import org.anarres.lzo.lzo_uintp;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Walks SquashFS 4 (and, through Squash3, 3.1) images file by file without extracting them first. */
public final class SquashFs {
    private SquashFs() {}

    // ===== SquashFS 4 =========================================================
    /** Receives the content of a SquashFS image, see [walkSquashFs]. Paths are relative to the root of the image. */
    public interface SquashVisitor {
        void dir(String path,int mode)throws Exception;
        void link(String path,String target)throws Exception;
        /** Returns the stream that receives the file's bytes (it is closed afterwards), or null to skip the file. */
        OutputStream file(String path,int mode,long size)throws Exception;
        /** Called after the stream returned by file() has been closed. */
        void fileEnd(String path)throws Exception;
    }
    /** Walks a SquashFS 4 image without extracting it, so that the caller can hash, compare or store single files. */
    public static void walkSquashFs(File image,Consumer<String>log,SquashVisitor v)throws Exception{
        // SquashFS 3.1 (Logitech Revue boot.img) has its own on-disk layout, see Squash3
        if(Squash3.probe(image)){try(Squash3 s=new Squash3(image,log)){s.walk(v);}return;}
        try(SquashReader s=new SquashReader(image,log)){s.walkVisit(v);}
    }
    /** True if the file starts with a SquashFS 4 superblock (either byte order). */
    public static boolean isSquashFs(File f){
        try(InputStream i=new FileInputStream(f)){byte[]h=new byte[4];int n=0;while(n<4){int k=i.read(h,n,4-n);if(k<=0)return false;n+=k;}
            return (h[0]=='h'&&h[1]=='s'&&h[2]=='q'&&h[3]=='s')||(h[0]=='s'&&h[1]=='q'&&h[2]=='s'&&h[3]=='h');}
        catch(IOException e){return false;}
    }
    static final class SquashReader implements Closeable {
        static final int META=8192,NO_FRAG=0xffffffff,UNCOMP=1<<24;RandomAccessFile r;Consumer<String>log;long base,imgEnd,bytesUsed,inodeTable,dirTable,fragTable,idTable,xattrTable,lookupTable,rootRef;int block,compression;boolean be;byte[]inodes,dirs;Map<Long,Integer>imap,dmap;List<Frag>frags=new ArrayList<>();
        SquashReader(File f,Consumer<String>l)throws Exception{r=new RandomAccessFile(f,"r");log=l;find();load();}
        void find()throws IOException{
            final int CH=1<<20,SB=96;long len=r.length(),limit=Math.min(len,64L<<20);byte[]buf=new byte[CH+SB];
            for(long start=0;start<limit;start+=CH){
                int n=(int)Math.min(CH+SB,len-start);if(n<SB)break;r.seek(start);r.readFully(buf,0,n);
                for(int i=0;i<CH&&i+SB<=n;i++){
                    int m=le32(buf,i);if(m!=0x73717368&&m!=0x68737173)continue;
                    boolean b=(m==0x68737173); // "sqsh" read little-endian = big-endian filesystem
                    int verMaj=u16(buf,i+28,b),bs=le32(buf,i+12,b),blog=u16(buf,i+22,b),comp=u16(buf,i+20,b);
                    if(verMaj==4&&bs>=4096&&bs<=1<<20&&(bs&(bs-1))==0&&bs==(1<<blog)&&comp>=1&&comp<=6){base=start+i;be=b;return;}
                }
            }
            throw new IOException("no SquashFS 4.0 superblock (only SquashFS 4.x and 3.1 are supported)");
        }
        void load()throws Exception{r.seek(base);byte[]s=new byte[96];r.readFully(s);int[]v=new int[5];for(int i=0;i<5;i++)v[i]=le32(s,i*4,be);block=v[3];compression=le16(s,20,be);rootRef=u64(s,32,be);bytesUsed=u64(s,40,be);inodeTable=u64(s,64,be);dirTable=u64(s,72,be);fragTable=u64(s,80,be);idTable=u64(s,48,be);xattrTable=u64(s,56,be);lookupTable=u64(s,88,be);r.seek(r.length());imgEnd=r.length()-base;MapTable it=loadMeta(inodeTable);inodes=it.buf;imap=it.map;MapTable dt=loadMeta(dirTable);dirs=dt.buf;dmap=dt.map;int fc=le32(s,16,be);if(fc>0&&fragTable!=0xffffffffffffffffL){long ptrs=(fc+511L)/512;byte[]pb=readAt(base+fragTable,(int)Math.min(ptrs*8,Integer.MAX_VALUE));for(int j=0;j<ptrs;j++){byte[]f=meta(u64(pb,j*8,be));int cnt=(int)Math.min(512,fc-j*512L);for(int k=0;k<cnt&&(k+1)*16<=f.length;k++){frags.add(new Frag(u64(f,k*16,be),le32(f,k*16+8,be)));}}}log.accept("SquashFS: block="+block+" compression="+compression+" endian="+(be?"BE":"LE"));}
        static final int SeekOrigin_END=2;
        MapTable loadMeta(long start)throws Exception{long end=imgEnd;long[]c={bytesUsed,fragTable,inodeTable,dirTable,idTable,xattrTable,lookupTable,0xffffffffffffffffL};for(long x:c)if(x!=0xffffffffffffffffL&&x>start&&x<=imgEnd)end=Math.min(end,x);ByteArrayOutputStream buf=new ByteArrayOutputStream();Map<Long,Integer>map=new HashMap<>();long off=start;while(off<end){map.put(off-start,buf.size());MetaBlock mb;try{mb=metaAt(off);}catch(Exception ex){if(buf.size()>0){map.remove(off-start);break;}throw ex;}buf.write(mb.data,0,mb.data.length);off=mb.next;}return new MapTable(buf.toByteArray(),map);}
        MetaBlock metaAt(long off)throws Exception{byte[]h=readAt(base+off,2);int v=le16(h,0,be),sz=v&0x7fff;if(sz==0)throw new IOException("bad SquashFS metadata block");byte[]raw=readAt(base+off+2,sz);byte[]d=(v&0x8000)!=0?raw:decomp(raw,META);return new MetaBlock(d,off+2+sz);}
        byte[]meta(long off)throws Exception{return metaAt(off).data;}
        byte[]decomp(byte[]raw,int expected)throws Exception{switch(compression){
            case 1:{java.util.zip.Inflater inf=new java.util.zip.Inflater();try{inf.setInput(raw);ByteArrayOutputStream o=new ByteArrayOutputStream(Math.max(expected,8192));byte[]b=new byte[8192];while(!inf.finished()){int n=inf.inflate(b);if(n>0){o.write(b,0,n);continue;}if(inf.needsDictionary())throw new IOException("SquashFS zlib needs a preset dictionary");if(inf.needsInput())break;}return o.toByteArray();}catch(java.util.zip.DataFormatException ex){throw new IOException("SquashFS zlib block is corrupt: "+ex.getMessage(),ex);}finally{inf.end();}}
            case 2:return ArcStreams.decodeAll(NativeArc.LZMA,raw,0,raw.length,expected);
            case 3:{LzoDecompressor d=LzoLibrary.getInstance().newDecompressor(LzoAlgorithm.LZO1X,null);byte[]out=new byte[Math.max(expected,1)];lzo_uintp n=new lzo_uintp(out.length);int code=d.decompress(raw,0,raw.length,out,0,n);if(code!=0)throw new IOException("SquashFS LZO error "+code);return Arrays.copyOf(out,(int)n.value);}
            case 4:return ArcStreams.decodeAll(NativeArc.XZ,raw,0,raw.length,expected);
            case 5:return lz4Block(raw,expected);
            case 6:{byte[]out=new byte[Math.max(expected,1)];long n=Zstd.decompress(out,raw);if(Zstd.isError(n))throw new IOException("SquashFS Zstd error: "+Zstd.getErrorName(n));return Arrays.copyOf(out,(int)n);}
            default:throw new IOException("SquashFS compression "+compression+" is not supported");}}
        static byte[] lz4Block(byte[]src,int expected)throws IOException {
            int cap=Math.max(expected,256),op=0,ip=0;byte[]out=new byte[cap];
            while(ip<src.length){
                int token=src[ip++]&255;
                int lit=token>>>4;if(lit==15){int x;do{if(ip>=src.length)throw new EOFException();x=src[ip++]&255;lit+=x;}while(x==255);}
                if(ip+lit>src.length)throw new IOException("LZ4 literal overruns block");
                if(op+lit>out.length)out=Arrays.copyOf(out,grow(out.length,op+lit));
                System.arraycopy(src,ip,out,op,lit);ip+=lit;op+=lit;
                if(ip>=src.length)break;
                if(ip+2>src.length)throw new IOException("LZ4 match offset truncated");
                int off=(src[ip]&255)|((src[ip+1]&255)<<8);ip+=2;
                if(off<=0||off>op)throw new IOException("LZ4 invalid match offset");
                int m=(token&15)+4;if((token&15)==15){int x;do{if(ip>=src.length)throw new EOFException();x=src[ip++]&255;m+=x;}while(x==255);}
                if(op+m>out.length)out=Arrays.copyOf(out,grow(out.length,op+m));
                for(int i=0;i<m;i++)out[op+i]=out[op-off+i];
                op+=m;
            }
            return Arrays.copyOf(out,op);
        }
        static int grow(int old,int need){int n=Math.max(256,old);while(n<need){int next=n+(n>>1);if(next<=n){n=need;break;}n=next;}return n;}

        Inode inode(long ref)throws Exception{int blk=(int)(ref>>>16),off=(int)ref&0xffff;Integer baseOff=imap.get((long)blk);if(baseOff==null)throw new IOException("inode ref outside table");int p=baseOff+off;int t=le16(inodes,p,be),mode=le16(inodes,p+2,be);int q=p+16;Inode x=new Inode(t,mode);if(t==1){x.db=le32(inodes,q,be);x.ds=le16(inodes,q+8,be);x.doff=le16(inodes,q+10,be);}else if(t==8){x.db=le32(inodes,q+8,be);x.ds=le32(inodes,q+4,be);x.doff=le16(inodes,q+18,be);}else if(t==2){x.start=le32(inodes,q,be)&0xffffffffL;x.frag=le32(inodes,q+4,be);x.fragOff=le32(inodes,q+8,be);x.size=le32(inodes,q+12,be)&0xffffffffL;int n=x.frag==NO_FRAG?ceil((int)x.size,block):(int)(x.size/block);x.sizes=new int[n];for(int i=0;i<n;i++)x.sizes[i]=le32(inodes,q+16+i*4,be);}else if(t==9){x.start=u64(inodes,q,be);x.size=u64(inodes,q+8,be);x.frag=le32(inodes,q+28,be);x.fragOff=le32(inodes,q+32,be);int n=x.frag==NO_FRAG?ceil((int)Math.min(Integer.MAX_VALUE,x.size),block):(int)(x.size/block);x.sizes=new int[n];for(int i=0;i<n;i++)x.sizes[i]=le32(inodes,q+40+i*4,be);}else if(t==3||t==10){int len=le32(inodes,q+4,be);x.target=new String(inodes,q+8,len,StandardCharsets.UTF_8);}return x;}
        List<Pair>dir(Inode i)throws Exception{List<Pair>o=new ArrayList<>();Integer dbase=dmap.get((long)i.db);if(dbase==null)return o;int p=dbase+i.doff;int end=p+i.ds-3;while(p+12<=end){int cnt=le32(dirs,p,be),start=le32(dirs,p+4,be);p+=12;for(int k=0;k<=cnt&&p+8<=end;k++){int off=le16(dirs,p,be),len=le16(dirs,p+6,be);p+=8;if(p+len+1>dirs.length)break;String n=new String(dirs,p,len+1,StandardCharsets.UTF_8);p+=len+1;o.add(new Pair(n,((long)start<<16)|(off&0xffffL)));}}return o;}
        void streamFile(OutputStream z,Inode in)throws Exception{long rem=in.size,pos=in.start;for(int sz:in.sizes){long n=Math.min(block,rem);int c=sz&0x00ffffff;if(c==0){writeZeros(z,n);}else{byte[]raw=readAt(base+pos,c);pos+=c;byte[]data=(sz&UNCOMP)!=0?raw:decomp(raw,block);int take=(int)Math.min(n,data.length);z.write(data,0,take);if(take<n)writeZeros(z,n-take);}rem-=n;if(rem<=0)break;}if(rem>0&&in.frag!=NO_FRAG&&in.frag<frags.size()){Frag f=frags.get(in.frag);byte[]raw=readAt(base+f.start,f.size&0x00ffffff);byte[]data=(f.size&UNCOMP)!=0?raw:decomp(raw,block);int off=Math.min(in.fragOff,data.length);int take=(int)Math.min(rem,data.length-off);z.write(data,off,take);rem-=take;}if(rem>0)writeZeros(z,rem);}
        /** Visits every folder, link and regular file of the image (the root folder itself is not reported). */
        void walkVisit(SquashVisitor v)throws Exception{walkV(v,rootRef,"",new HashSet<Long>());}
        void walkV(SquashVisitor v,long ref,String rel,Set<Long>seen)throws Exception{
            Inode in=inode(ref);
            if(in.type==1||in.type==8){
                if(!rel.isEmpty())v.dir(rel,in.mode&07777);
                if(!seen.add(ref))return;
                for(Pair p:dir(in)){if(p.name.equals(".")||p.name.equals("..")||p.name.indexOf('/')>=0)continue;walkV(v,p.ref,rel.isEmpty()?p.name:rel+"/"+p.name,seen);}
            }else if(in.type==2||in.type==9){
                OutputStream o=v.file(rel,in.mode&07777,in.size);
                if(o!=null){try{streamFile(o,in);}finally{o.close();}v.fileEnd(rel);}
            }else if(in.type==3||in.type==10){
                v.link(rel,in.target);
            }
        }
        byte[]readAt(long pos,int n)throws IOException{r.seek(pos);byte[]b=new byte[n];r.readFully(b);return b;}static final class MapTable{byte[]buf;Map<Long,Integer>map;MapTable(byte[]b,Map<Long,Integer>m){buf=b;map=m;}} static final class MetaBlock{byte[]data;long next;MetaBlock(byte[]d,long n){data=d;next=n;}}static final class Frag{long start;int size;Frag(long s,int z){start=s;size=z;}}static final class Inode{int type,mode,db,ds,doff,frag,fragOff;long size,start;int[]sizes=new int[0];String target="";Inode(int t,int m){type=t;mode=m;}}static final class Pair{String name;long ref;Pair(String n,long r){name=n;ref=r;}}
        public void close()throws IOException{r.close();}
    }

    // ===== little helpers of the original toolset, used by the reader above =====
    static void writeZeros(OutputStream z,long n)throws IOException{byte[]b=new byte[1<<16];while(n>0){int k=(int)Math.min(n,b.length);z.write(b,0,k);n-=k;}}
    static long u32(byte[]b,int o){return (b[o]&255L)|((b[o+1]&255L)<<8)|((b[o+2]&255L)<<16)|((b[o+3]&255L)<<24);}
    static int le16(byte[]b,int o){return (b[o]&255)|((b[o+1]&255)<<8);}
    static int le16(byte[]b,int o,boolean be){return be?(((b[o]&255)<<8)|(b[o+1]&255)):le16(b,o);}
    static int u16(byte[]b,int o,boolean be){return be?(((b[o]&255)<<8)|(b[o+1]&255)):le16(b,o);}
    static int le32(byte[]b,int o){return (int)u32(b,o);}
    static int le32(byte[]b,int o,boolean be){return be?((b[o]&255)<<24)|((b[o+1]&255)<<16)|((b[o+2]&255)<<8)|(b[o+3]&255):le32(b,o);}
    static long u64(byte[]b,int o){return u32(b,o)|(u32(b,o+4)<<32);}
    static long u64(byte[]b,int o,boolean be){if(!be)return u64(b,o);return ((long)le32(b,o,true)&0xffffffffL)<<32 | ((long)le32(b,o+4,true)&0xffffffffL);}
    static int ceil(int n,int d){return (n+d-1)/d;}
}
