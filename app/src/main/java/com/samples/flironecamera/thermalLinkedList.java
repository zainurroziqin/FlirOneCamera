package com.samples.flironecamera;

public class thermalLinkedList
{
    Node head;
    static class Node{
        double data;
        Node next;

        Node(double d)
        {
            data = d;
            next = null;
        }

    }
    public  static thermalLinkedList tambah(thermalLinkedList list, double data){
        Node new_node = new Node(data);
        if(list.head == null){
            list.head = new_node;
        }
        else{
            Node last = list.head;
            while (last.next != null){
                last = last.next;
            }
            last.next = new_node;
        }
        return list;
    }
}
