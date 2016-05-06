import socket
import sys
import time

NUMBER_OF_TURNS = 2
PLAYER_NAME = sys.argv[1]


class ChanceItClient:
    '''
       ChanceIt Test Client
    '''

    def __init__(self, sock=None):
        if sock is None:
            self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        else:
            self.sock = sock

    def connect(self, host, port):
        self.sock.connect((host, port))

    def send(self, msg):
        totalsent = 0
        MSGLEN = len(msg)
        while totalsent < MSGLEN - 1:
            sent = self.sock.send(msg[totalsent:])
            if sent == 0:
                raise RuntimeError("socket connection broken")
            totalsent = totalsent + sent

    def readLine(self, msg=""):
        '''
            Read a char at a time up to a '\n'
            This is inefficient however it is simple than alternatives
        '''
        line = ""
        while True:
            chunk = self.sock.recv(1)
            if chunk == "" or chunk == "\n":
                break
            line += chunk
        return line


if __name__ == "__main__":
    client = ChanceItClient()
    client.connect('localhost', 1099)

    # identify yourself
    client.send("HELLO:%s\n" % PLAYER_NAME)

    # assert you get confirmation with the "IS IT ME YOUR LOOKING FOR"
    data = client.readLine()
    # print data
    if data.startswith("IS IT ME YOU'RE LOOKIN FOR?"):
        print "got HELLO response"
    else:
        print data
#    print "HERE HERE HERE"

    '''
    # assert you get Welcome "name"
    data = client.readLine()
    print data
    if data.startswith("WELCOME "):
        print "got Welcome response"
    else:
        print data
        '''

    # assert you get the OPPONENT message
    data = client.readLine()
    print "data is: ###\n%s\n###" % data
    if data.startswith("Opponent: "):
        # get your opponent
        opponent = data[len("Opponent: "):]
        print "Opponent is: %s" % opponent

    # get this player's first roll
    data = client.readLine()
    if data.startswith("Your roll was: "):
        # get your starting roll
        roll = data[len("Your roll was: "):data.find(". Opponent roll was:")]
        print "roll was: %s" % roll

    # print "entering turn loop"
    turnNumber = rollNumber = totalScore = score = 0
    counter = 0
    while (True):
        counter += 1
        '''
            Turn#: 1
            Roll#: 2
            Turn Starting Score: 16-38
            Running Turn Score: 23
            Roll Score: 8
            You Rolled: [4,4]
            --
            chance-it? [Y/n]
        '''
        data = client.readLine()
        print data
        if data.startswith("Turn#: "):
            turnNumber = int(data[len("Turn#: "):])
            print "_turn: %s" % turnNumber
        '''
        else:

            while data.startswith("Your roll was: "):
                data = client.readLine()

            if data.startswith("Turn#: "):
                turnNumber = int(data[len("Turn#: "):])
                print "_turn: %s" % turnNumber
            '''

        data = client.readLine()
        if data.startswith("Roll#: "):
            rollNumber = int(data[len("Roll#: "):])
            print "_roll: %s" % rollNumber

        data = client.readLine()
        if data.startswith("Score: "):
            totalScore = int(data[len("Turn Starting Score: "):data.find("-")])
            print "_total score: %s __total score: %s" % (totalScore, data)

        data = client.readLine()
        if data.startswith("Running Turn Score: "):
            score = int(data[len("Running Turn Score: "):])
            print "_score: %s" % score

        data = client.readLine()
        if data.startswith("Roll Score: "):
            roll = int(data[len("Roll Score: "):])
            print "_roll_score: %s" % roll

        data = client.readLine()
        if data.startswith("You Rolled: []"):
            roll = int(data[len("You Rolled: "):])
            print "_rolled: %s" % roll

        data = client.readLine()
        if data.startswith("--"):
            print "_separater: %s" % data

        '''
            When score == 0 we've rolled the same value as our first roll for this turn.
            The turn is finised and the total score for this turn is 0.
            else we can roll again by sending 'chance-it' to the server
        '''
        if 0 == score and turnNumber == NUMBER_OF_TURNS:
            data = client.readLine()
            print data
            break
        elif 0 != score:
            # we did not roll the same value as the first roll for this turn :)
            # assert we get the 'chance-it?' prompt

            data = client.readLine()
            # print "!! %s" % data
            if data.startswith("chance-it? [Y/n]"):
                # depending on what you want to do, chance-it
                if score < 30:
                    time.sleep(1)
                    print "# chance-it [CR]"
                    # client.send("chance-it\n")
                    client.send("\r\n")
                else:
                    print "# 'n'"
                    client.send("n\n")
                    if turnNumber == NUMBER_OF_TURNS:
                        # the game is over so block on a read from the socket to learn who won
                        data = client.readLine()
                        print data
                        break
