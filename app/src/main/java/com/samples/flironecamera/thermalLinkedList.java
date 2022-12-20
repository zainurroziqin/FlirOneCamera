package com.samples.flironecamera;

class Node{
    double data;
    Node next;
    Node(double data){
        this.data = data;
    }
}
class ThermalLinkedList {
    Node head;

    public void insert(double data) {
        Node newNode = new Node(data);
        if(head == null) {
            head = newNode;
        }else {
            Node currentNode = head;
            while(currentNode.next != null) {
                currentNode= currentNode.next;
            }
            currentNode.next = newNode;
        }
    }

    public void insertAtStart(double data) {
        Node newNode = new Node(data);

        newNode.next = head;
        head = newNode;
    }

    public void insertAt(int index, double data) {
        if(index == 0){
            insertAtStart(data);
        }else{
            Node newNode = new Node(data);

            Node currentNode = head;
            for(int i = 0; i < index - 1; i++) {
                currentNode = currentNode.next;
            }
            newNode.next = currentNode.next;
            currentNode.next = newNode;
        }
    }
    public String hasil() {
        Node currentNode = head;
        String hasil = null;
        if(currentNode == null){
//            System.out.println("Linked list is empty");
            hasil = "kosong";
        }
        else {
            while(currentNode != null) {
//                System.out.print(currentNode.data + " ");
                hasil = currentNode.data + " ";
                currentNode = currentNode.next;
            }
        }
        return hasil;
    }

}