package com.horizen.utils;

import com.google.common.primitives.Ints;
import scala.util.Failure;
import scala.util.Success;
import scala.util.Try;
import scorex.core.serialization.BytesSerializable;
import scorex.core.serialization.ScorexSerializer;
import scorex.util.serialization.Reader;
import scorex.util.serialization.Writer;

import java.io.ByteArrayOutputStream;
import java.util.*;

public class ListSerializer<T extends BytesSerializable> implements ScorexSerializer<List<T>> {
    private ScorexSerializer<T> _serializer;
    private int _maxListLength; // Used during parsing bytes. Not positive value for unlimited lists support.

    public ListSerializer(ScorexSerializer<T> serializer) {
        this(serializer, 0);
    }

    public ListSerializer(ScorexSerializer<T> serializer, int maxListLength) {
        _maxListLength = maxListLength;
        _serializer = serializer;
    }


    @Override
    public byte[] toBytes(List<T> obj) {
        // Serialized data has the next order:
        // Size of an array with List<T> objects lengths    4 bytes
        // Array with objects length                        4 bytes multiplied by value above
        // Array with objects                               rest of bytes

        List<Integer> lengthList = new ArrayList<>();

        ByteArrayOutputStream res = new ByteArrayOutputStream();
        ByteArrayOutputStream entireRes = new ByteArrayOutputStream();
        for (T t : obj) {
            // We have single serializer defined, so we don't need to store Serializer ID.
            byte[] tBytes = _serializer.toBytes(t);
            lengthList.add(tBytes.length); // size of serialized T object
            entireRes.write(tBytes, 0, tBytes.length);
        }

        res.write(Ints.toByteArray(lengthList.size()), 0, 4);
        for (Integer i : lengthList) {
            res.write(Ints.toByteArray(i), 0, 4);
        }
        byte[] entireResBytes = entireRes.toByteArray();
        res.write(entireResBytes, 0 , entireResBytes.length);

        return res.toByteArray();
    }

    @Override
    public Try<List<T>> parseBytesTry(byte[] bytes) {
        try {
            int offset = 0;

            if(bytes.length < 4)
                throw new IllegalArgumentException("Input data corrupted.");
            int lengthListSize = BytesUtils.getInt(bytes, offset);
            offset += 4;

            if(_maxListLength > 0 && lengthListSize > _maxListLength)
                throw new IllegalArgumentException("Input data contains to many elements.");

            if(bytes.length < offset + lengthListSize * 4)
                throw new IllegalArgumentException("Input data corrupted.");
            int objectsTotalLength = 0;
            ArrayList<Integer> lengthList = new ArrayList<>();
            while(offset < 4 * lengthListSize + 4) {
                int objectLength = BytesUtils.getInt(bytes, offset);
                objectsTotalLength += objectLength;
                lengthList.add(objectLength);
                offset += 4;
            }

            if(bytes.length != offset + objectsTotalLength)
                throw new IllegalArgumentException("Input data corrupted.");

            ArrayList<T> res = new ArrayList<>();
            for(int length : lengthList) {
                Try<T> t = _serializer.parseBytesTry(Arrays.copyOfRange(bytes, offset, offset + length));
                if (t.isFailure())
                    throw new IllegalArgumentException("Input data corrupted.");
                res.add(t.get());
                offset += length;
            }

            return new Success<>(res);
        }
        catch(IllegalArgumentException e) {
            return new Failure<>(e);
        }
    }

    //TODO finish implementation
    @Override
    public void serialize(List<T> objectList, Writer writer) {
    }

    @Override
    public List<T> parse(Reader reader) {
        return null;
    }

    /*
    @Override
    public void serialize(List<T> objectList, Writer writer) {
        List<Integer> lengthList = new ArrayList<>();
        ByteArrayOutputStream objects = new ByteArrayOutputStream();

        for(T object : objectList) {
            byte[] objectBytes = object.bytes();
            lengthList.add(objectBytes.length);
            objects.write(objectBytes, 0, objectBytes.length);
        }

        writer.putInt(4 + lengthList.size()*4 + objects.size());

        writer.putInt(objectList.size());

        for(Integer length : lengthList)
            writer.putInt(length);

        writer.putBytes(objects.toByteArray());

    }

    @Override
    public List<T> parse(Reader reader) {

        int overallSize = reader.getInt();

//        if(reader.remaining() < overallSize)
//            throw new IllegalArgumentException("Input data corrupted.");

        int objectCount = reader.getInt();

//        if(objectCount <= 0)
//            throw new IllegalArgumentException("Input data contains illegal elements count - " + objectCount);

        if(_maxListLength > 0 && objectCount > _maxListLength)
            throw new IllegalArgumentException("Input data contains to many elements - " + objectCount);

        if(reader.remaining() < objectCount * 4)
            throw new IllegalArgumentException("Input data corrupted.");

        ArrayList<Integer> lengthList = new ArrayList<>();

        for(int i = 0; i < objectCount; i++)
            lengthList.add(reader.getInt());

        ArrayList<T> objectList = new ArrayList<>(objectCount);

        for (Integer i : lengthList) {
            if (reader.remaining() < i)
                throw new IllegalArgumentException("Input data corrupted.");
            objectList.add(_serializer.parseBytes(reader.getBytes(i)));
        }

        return objectList;
    }
    */
}