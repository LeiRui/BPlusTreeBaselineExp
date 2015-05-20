package tuc.ds.bptree;

import sun.plugin.dom.exception.InvalidStateException;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.InvalidPropertiesFormatException;
import java.util.LinkedList;

public class BPlusTree {

    public TreeNode root;//, nBuf;
    private TreeNode leftChild;
    private RandomAccessFile treeFile;
    private BPlusConfiguration conf;
    private LinkedList<Long> freeSlotPool;
    private long totalTreePages;
    private LinkedList<String> queryResultValues;

    public BPlusTree(BPlusConfiguration conf)
            throws IOException {
        this.conf = conf;
        this.totalTreePages = 0L;
        this.freeSlotPool = new LinkedList<>();
        openFile("tree.bin", "rw+", conf);
    }

    public BPlusTree(BPlusConfiguration conf, String treeFilePath)
            throws IOException {
        this.conf = conf;
        this.freeSlotPool = new LinkedList<>();
        this.totalTreePages = 0L;
        openFile(treeFilePath, "rw", conf);
    }

    public void insertKey(long key, String value) throws IOException {

        if(root == null)
            {throw new IllegalStateException("Can't insert to null tree");}

        if(key < 0)
            {throw new NumberFormatException("Can't have negative keys, sorry.");}

        // check if our root is full
        if(root.isFull(conf)) {
            // allocate a new *internal* node
            leftChild = this.root;
            TreeInternalNode tbuf = new TreeInternalNode(TreeNodeType.TREE_ROOT_INTERNAL,
                    generateFirstAvailablePageIndex(conf));
            tbuf.addPointerAt(0, leftChild.getPageIndex());
            this.root = tbuf;

            // split root.
            printTree();
            splitTreeNode(tbuf, 0);
            printTree();
            insertNonFull(tbuf, key, value);
        }
        else
            {insertNonFull(root, key, value);}
    }

    /**
     * It requires *3* page writes plus commit of the updated page
     * count to the file header
     *
     * @param n internal node "parenting" the split
     * @param index index in the node n that we need to add the median
     */

    //TODO
    private void splitTreeNode(TreeInternalNode n, int index) throws IOException {

        System.out.println("-- Splitting node with index: " +
                leftChild.getPageIndex() + " of type: " +
                leftChild.getNodeType().toString());

        int setIndex = 0;

        TreeNode znode;
        long keyToAdd = -1L;
        TreeNode ynode = leftChild; // x.c_{i}
        if(ynode.isInternalNode()) {
            znode = new TreeInternalNode(TreeNodeType.TREE_INTERNAL_NODE,
                    generateFirstAvailablePageIndex(conf));

            setIndex =  conf.getTreeDegree()-1;

            int i;
            for(i = 0; i < setIndex; i++) {
                znode.addToKeyArrayAt(i, ynode.popKey());
                ((TreeInternalNode)znode).addPointerAt(i,
                        ((TreeInternalNode)ynode).popPointer());
            }
            ((TreeInternalNode)znode).addPointerAt(i,
                    ((TreeInternalNode)ynode).popPointer());
            keyToAdd = ynode.popKey();

            znode.setCurrentCapacity(setIndex);
            ynode.setCurrentCapacity(setIndex);

            // it it was the root, invalidate it and make it a regular internal node
            if(ynode.isRoot())
                {ynode.setNodeType(TreeNodeType.TREE_INTERNAL_NODE);}

            // update pointer at n_{index+1}
            n.addPointerAt(index, znode.getPageIndex());
            // update key value at n[index]
            n.addToKeyArrayAt(index, keyToAdd);
            // adjust capacity
            n.incrementCapacity();


        }
        // we have a leaf
        else {
            znode = new TreeLeaf(((TreeLeaf)ynode).getNextPagePointer(),
                    ynode.getPageIndex(), TreeNodeType.TREE_LEAF,
                    generateFirstAvailablePageIndex(conf));

            // update pointers in ynode, only have to update next pointer
            ((TreeLeaf)ynode).setNextPagePointer(znode.getPageIndex());

            setIndex = conf.getLeafNodeDegree()-1;

            for(int i = 0; i < setIndex; i++) {
                znode.pushToKeyArray(ynode.removeLastKey());
                ((TreeLeaf)znode).pushToValueList(((TreeLeaf)ynode).removeLastValue());
                znode.incrementCapacity();
                ynode.decrementCapacity();
            }

            // it it was the root, invalidate it and make it a regular leaf
            if(ynode.isRoot())
                {ynode.setNodeType(TreeNodeType.TREE_LEAF);}

            // update pointer at n_{index+1}
            n.addPointerAt(index+1, znode.getPageIndex());
            // update key value at n[index]
            n.addToKeyArrayAt(index, znode.getKeyAt(0));
            // adjust capacity
            n.incrementCapacity();
        }

        // commit the changes
        znode.writeNode(treeFile, conf);
        ynode.writeNode(treeFile, conf);
        n.writeNode(treeFile, conf);
        // commit page counts
        treeFile.seek(conf.getPageCountOffset());
        treeFile.writeLong(totalTreePages);

    }

    private void insertNonFull(TreeNode n, long key, String value) throws IOException {
        boolean goLeft = true;
        int i = n.getCurrentCapacity()-1;
        // descend down the node
        while(i >= 0 && key < n.getKeyAt(i)) {i--;}
        // correction
        i++;
        // check if we have a leaf
        if(n.isLeaf()) {
            TreeLeaf l = (TreeLeaf)n;

            // now add the (Key, Value) pair
            l.addToValueList(i, value);
            l.addToKeyArrayAt(i, key);
            l.incrementCapacity();

            // commit the changes
            l.writeNode(treeFile, conf);

        } else {
            TreeInternalNode inode = (TreeInternalNode)n;
            leftChild = readNode(inode.getPointerAt(i));

            //printTree();
            TreeNode tmpRight = null;
            if(leftChild.isFull(conf)) {
                splitTreeNode(inode, i);
                if (key > n.getKeyAt(i)) {
                    goLeft = false;
                    tmpRight = readNode(inode.getPointerAt(i+1));
                }
            }

            insertNonFull(goLeft ? leftChild : tmpRight, key, value);
        }
    }

    public RangeResult rangeSearch(long minKey, long maxKey) throws IOException {
        SearchResult sMin = searchKey(minKey);
        SearchResult sMax = null;
        RangeResult rangeQueryResult = new RangeResult();
        if(sMin.isFound()) {
            // read up until we find a key that's greater than maxKey
            // or the last entry.

            int i = sMin.getIndex();
            while(sMin.getLeaf().getKeyAt(i) <= maxKey) {
                rangeQueryResult.getQueryResult().
                        add(new KeyValueWrapper(sMin.getLeaf().getKeyAt(i),
                                sMin.getLeaf().getValueAt(i)));
                i++;

                // check if we need to read the next block
                if(i == sMin.getLeaf().getCurrentCapacity()) {
                    // check if we have a next node to load.
                    if(sMin.getLeaf().getNextPagePointer() < 0)
                        // if not just break the loop
                        {break;}
                    sMin.setLeaf((TreeLeaf)readNode(sMin.getLeaf().getNextPagePointer()));
                    i = 0;
                }
            }

        }
        // this is the case where both searches might fail to find something, but
        // we *might* have something between in the given range. To account for
        // that even if we have *not* found something we will return those results
        // instead. For example say we have a range of [2, 5] and we only have keys
        // from [3, 4], thus both searches for min and max would fail to find a
        // matching key in both cases. Thing is to account for that *both* results
        // will be stopped at the first key that is less than min and max values
        // given even if we did not find anything.
        else {
            sMax = searchKey(maxKey);

            int i = sMax.getIndex();
            while(sMax.getLeaf().getKeyAt(i) >= minKey) {
                rangeQueryResult.getQueryResult().
                        add(new KeyValueWrapper(sMax.getLeaf().getKeyAt(i),
                                sMax.getLeaf().getValueAt(i)));
                i--;

                // check if we need to read the next block
                if(i < 0) {
                    // check if we do have another node to load
                    if(sMax.getLeaf().getPrevPagePointer() < 0)
                    // if not just break the loop
                        {break;}
                    sMax.setLeaf((TreeLeaf)readNode(sMax.getLeaf().getPrevPagePointer()));
                    // set it to max length
                    i = sMax.getLeaf().getCurrentCapacity()-1;
                }
            }

        }


        return(rangeQueryResult);
    }

    public SearchResult searchKey(long key)
            throws IOException
        {return(searchKey(this.root, key));}


    public SearchResult searchKey(TreeNode node, long key) throws IOException {

        int i = 0;

        while(i < node.getCurrentCapacity() && key >= node.getKeyAt(i)) {i++;}

        // check if we found it
        if(node.isLeaf()) {
            i--;
            //TreeLeaf leaf = (TreeLeaf) readNode(((TreeLeaf)node).getNextPagePointer());

            //TreeLeaf leaf1 = (TreeLeaf) readNode(((TreeLeaf)leaf).getNextPagePointer());
            if(i < node.getCurrentCapacity() && key == node.getKeyAt(i))
                {return(new SearchResult((TreeLeaf)node, i, true));}
            else
                {return(new SearchResult((TreeLeaf)node, i, false));}

        }
        // probably it's an internal node, descend to a leaf
        else {
            TreeNode t = readNode(((TreeInternalNode)node).getPointerAt(i));
            return(searchKey(t, key));
        }

    }

    public int deleteKey(long key, boolean unique) {
        return(traverseAndDelete(root, key, unique));
    }

    private int traverseAndDelete(TreeNode parent, long key,
                                  boolean unique) {
        return(0);
    }

    private TreeNode createTree() throws IOException {
        if(root == null) {
            root = new TreeLeaf(-1, -1,
                    TreeNodeType.TREE_ROOT_LEAF,
                    generateFirstAvailablePageIndex(conf));
            // write the file
            root.writeNode(treeFile, conf);
        }
        return(root);
    }


    private TreeNodeType getPageType(short pval)
            throws InvalidPropertiesFormatException {
        switch(pval) {

            case 1:         // LEAF
                {return(TreeNodeType.TREE_LEAF);}

            case 2:         // INTERNAL NODE
                {return(TreeNodeType.TREE_INTERNAL_NODE);}

            case 3:         // INTERNAL NODE /w ROOT
                {return(TreeNodeType.TREE_ROOT_INTERNAL);}

            case 4:         // LEAF NODE /w ROOT
                {return(TreeNodeType.TREE_ROOT_LEAF);}

            case 5:         // LEAF OVERFLOW NODE
                {return(TreeNodeType.TREE_LEAF_OVERFLOW);}

            default: {
                throw new InvalidPropertiesFormatException("Unknown " +
                        "node value read; file possibly corrupt?");
            }
        }
    }

    private long calculatePageOffset(long index)
        {return(conf.getPageSize()*(index+1));}

    /**
     * Read each tree node and return it as a generic type
     *
     * @param index index in file
     * @return a TreeNode object referencing to the loaded page
     * @throws IOException
     */
    private TreeNode readNode(long index) throws IOException {
        treeFile.seek(0L);
        treeFile.seek(calculatePageOffset(index));
        // get the page type
        TreeNodeType nt = getPageType(treeFile.readShort());

        if(isInternalNode(nt)) {
            TreeInternalNode tnode = new TreeInternalNode(nt, index);
            int curCap = treeFile.readInt();
            for(int i = 0; i < curCap; i++) {
                tnode.addToKeyArrayAt(i, treeFile.readLong());
                tnode.addPointerAt(i, treeFile.readLong());
            }
            // add the final pointer
            tnode.addPointerAt(curCap, treeFile.readLong());
            // update the capacity
            tnode.setCurrentCapacity(curCap);
            return(tnode);
        } else {
            long nextptr = treeFile.readLong();
            long prevptr = treeFile.readLong();
            int curCap = treeFile.readInt();
            byte[] strBuf = new byte[conf.getEntrySize()];
            TreeLeaf tnode = new TreeLeaf(nextptr, prevptr, nt, index);

            // read entries
            for(int i = 0; i < curCap; i++) {
                tnode.addToKeyArrayAt(i, treeFile.readLong());
                treeFile.read(strBuf);
                tnode.addToValueList(i, new String(strBuf));
            }
            // update capacity
            tnode.setCurrentCapacity(curCap);
            return(tnode);
        }
    }

    private boolean isInternalNode(TreeNodeType nt) {
        return(nt == TreeNodeType.TREE_INTERNAL_NODE ||
                nt == TreeNodeType.TREE_ROOT_INTERNAL);
    }

    public boolean isLeaf(TreeNodeType nt) {
        return(nt == TreeNodeType.TREE_LEAF ||
                nt == TreeNodeType.TREE_LEAF_OVERFLOW ||
                nt == TreeNodeType.TREE_ROOT_LEAF);
    }

    /**
     * Reads an existing file and generates a B+ configuration based on the stored values
     *
     * @param r file to read from
     * @param generateConf generate configuration?
     * @return new configuration based on read values (if enabled) or null
     * @throws IOException
     */
    private BPlusConfiguration readFileHeader(RandomAccessFile r, String fname,
                                              boolean generateConf) throws IOException {
        r.seek(0L);

        // read the header number
        int headerNumber = r.readInt();

        if(headerNumber < 0)
            {throw new InvalidStateException("Negative header number found...");}

        // read the page size
        int pageSize = r.readInt();

        if(pageSize < 0)
            {throw new InvalidStateException("Cannot create a tree with negative page size");}

        // read the entry size
        int entrySize = r.readInt();

        if(entrySize <= 0)
            {throw new InvalidStateException("Entry size must be > 0");}

        // key size
        int keySize = r.readInt();

        if(keySize > 8 || keySize < 4)
            {throw new InvalidStateException("Key size but be either 4 or 8 bytes");}

        // read the number of pages (excluding the lookup)
        totalTreePages = r.readLong();

        if(totalTreePages < 0)
            {throw new InvalidStateException("Tree page number cannot be < 0");}

        // read the root index
        long rootIndex = r.readLong();

        if(rootIndex < 0)
            {throw new InvalidStateException("Root can't have index < 0");}

        // read the root.
        root = readNode(pageSize * (rootIndex+1));

        // finally if needed create a configuration file
        if(generateConf)
            {return(new BPlusConfiguration(pageSize, keySize, entrySize, fname));}
        else
            {return(null);}
    }

    private void openFile(String path, String mode, BPlusConfiguration opt)
            throws IOException {
        File f = new File(path);
        String stmode = mode.substring(0, 2);
        treeFile = new RandomAccessFile(path, stmode);
        // check if the file already exists
        if(f.exists() && !mode.contains("+")) {
            // read the header
            conf = readFileHeader(treeFile, f.getName(), true);
            // read the lookup page
            initializeLookupPage(f.exists());
        }
        // if we have to start anew, do so.
        else {
            //if(r == null) {throw new NullPointerException();}
            treeFile.setLength(0);
            conf = opt == null ? new BPlusConfiguration() : opt;
            initializeLookupPage(false);
            createTree();
        }
    }

    public void commitTree() throws IOException
        {this.treeFile.close();}

    private void initializeLookupPage(boolean exists) throws IOException {
        // get to the beginning of the file after the header
        this.treeFile.seek(conf.getHeaderSize());

        // check if already have a page, if not create it
        if(!exists) {
            for(int i = 0; i < conf.getLookupPageDegree(); i++)
                {this.treeFile.writeLong(-1);}
        }
        // if we do, read it.
        else {
            long val;
            for(int i = 0; i < conf.getLookupPageDegree(); i++) {
                if((val = this.treeFile.readLong()) == -1) {break;}
                this.freeSlotPool.add(val);
            }
        }
    }

    /**
     * Generate the first available index for a page.
     *
     * @param conf B+ configuration reference
     * @return page index
     */
    private long generateFirstAvailablePageIndex(BPlusConfiguration conf) {
        long index;
        // check if we have used pages
        if(freeSlotPool.size() > 0)
            {index = freeSlotPool.pop(); totalTreePages++; return(index);}
        // if not pad to the end of the file.
        else
            {index = conf.getPageSize() * (totalTreePages + 1); totalTreePages++; return(index);}
    }

    public void printTree() throws IOException {
        root.printNode();

        if(root.isInternalNode()) {
            TreeInternalNode t = (TreeInternalNode)root;
            long ptr = -1L;
            for(int i = 0; i < t.getCurrentCapacity()+1; i++) {
                ptr = t.getPointerAt(i);
                if(ptr < 0) {break;}
                printNodeAt(ptr);
            }
        }

    }

    public void printNodeAt(long index) throws IOException {
        TreeNode t = readNode(index);
        t.printNode();

        if(t.isInternalNode()) {
            TreeInternalNode t2 = (TreeInternalNode)t;
            long ptr = -1L;
            for(int i = 0; i < t2.getCurrentCapacity()+1; i++) {
                ptr = t2.getPointerAt(i);
                if(ptr < 0) {break;}
                printNodeAt(ptr);
            }
        }
    }

}